package ca.pkay.rcloneexplorer.thumbnails;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SyncLog;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.file.FileAccessError;

/**
 * Generates high-quality image/video thumbnails and keeps them in a persistent
 * application-private cache. A cache key contains the remote, path, size and
 * modification time, so changed remote files naturally receive a new entry.
 */
public final class ThumbnailRepository {

    public interface Callback {
        void onThumbnailLoaded(@Nullable Bitmap bitmap);
    }

    public interface Cancellable {
        void cancel();
    }

    public interface CacheStatisticsCallback {
        void onCacheStatisticsLoaded(CacheStatistics statistics);
    }

    public interface CacheExportCallback {
        void onCacheExportFinished(CacheExportResult result);
    }

    public interface CacheImportCallback {
        void onCacheImportFinished(CacheImportResult result);
    }

    public enum CacheType {
        IMAGE("image"),
        VIDEO("video");

        private final String archiveName;

        CacheType(String archiveName) {
            this.archiveName = archiveName;
        }

        private String getArchiveName() {
            return archiveName;
        }
    }

    public enum CacheArchiveError {
        NONE,
        IO_ERROR,
        INVALID_ARCHIVE,
        INCOMPATIBLE_CACHE_VERSION,
        EMPTY_ARCHIVE
    }

    /**
     * Result of a disk-only background thumbnail pre-generation request.
     * No display bitmap is decoded or inserted into the memory cache.
     */
    public enum PreGenerationResult {
        GENERATED,
        ALREADY_CACHED,
        RECENT_FAILURE,
        DISABLED,
        UNSUPPORTED,
        FAILED
    }

    public static final class CacheStatistics {
        private final int thumbnailCount;
        private final long totalBytes;

        private CacheStatistics(int thumbnailCount, long totalBytes) {
            this.thumbnailCount = thumbnailCount;
            this.totalBytes = totalBytes;
        }

        public int getThumbnailCount() {
            return thumbnailCount;
        }

        public long getTotalBytes() {
            return totalBytes;
        }
    }

    public static final class CacheExportResult {
        private final boolean successful;
        private final int thumbnailCount;
        private final long totalBytes;
        private final CacheArchiveError error;

        private CacheExportResult(boolean successful, int thumbnailCount, long totalBytes,
                                  CacheArchiveError error) {
            this.successful = successful;
            this.thumbnailCount = thumbnailCount;
            this.totalBytes = totalBytes;
            this.error = error;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public int getThumbnailCount() {
            return thumbnailCount;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public CacheArchiveError getError() {
            return error;
        }
    }

    public static final class CacheImportResult {
        private final boolean successful;
        private final int addedCount;
        private final int replacedCount;
        private final int skippedCount;
        private final long totalBytes;
        private final CacheArchiveError error;

        private CacheImportResult(boolean successful, int addedCount, int replacedCount,
                                  int skippedCount, long totalBytes,
                                  CacheArchiveError error) {
            this.successful = successful;
            this.addedCount = addedCount;
            this.replacedCount = replacedCount;
            this.skippedCount = skippedCount;
            this.totalBytes = totalBytes;
            this.error = error;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public int getAddedCount() {
            return addedCount;
        }

        public int getReplacedCount() {
            return replacedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public CacheArchiveError getError() {
            return error;
        }
    }

    private static final Cancellable NO_OP_CANCELLABLE = () -> { };

    private static final String TAG = "ThumbnailRepository";
    private static final String CACHE_ROOT_VERSION = "split-media-thumbnail-v1";
    private static final String IMAGE_CACHE_VERSION = "image-adapt-extract-v1";
    private static final String VIDEO_CACHE_VERSION = "video-third-dynamic-v1";
    private static final String THUMBNAIL_DIRECTORY_NAME = "thumbnail_cache";
    private static final String IMAGE_THUMBNAIL_DIRECTORY_NAME = "images";
    private static final String VIDEO_THUMBNAIL_DIRECTORY_NAME = "videos";
    private static final String WORK_DIRECTORY_NAME = "thumbnail_work";
    private static final String IMAGE_WORK_DIRECTORY_NAME = "images";
    private static final String VIDEO_WORK_DIRECTORY_NAME = "videos";
    private static final String CACHE_ROOT_VERSION_MARKER_NAME = "cache.root.version";
    private static final String CACHE_VERSION_MARKER_NAME = "cache.version";
    private static final String IMAGE_CACHE_STATE_PREFERENCES = "thumbnail_image_cache_state";
    private static final String VIDEO_CACHE_STATE_PREFERENCES = "thumbnail_video_cache_state";
    private static final String CACHE_STATE_VALID = "statistics_valid";
    private static final String CACHE_STATE_VERSION = "cache_version";
    private static final String CACHE_STATE_COUNT = "thumbnail_count";
    private static final String CACHE_STATE_BYTES = "thumbnail_bytes";
    private static final String ARCHIVE_MANIFEST_ENTRY = "thumbnail-cache.properties";
    private static final String IMAGE_ARCHIVE_THUMBNAIL_PREFIX = "image-thumbnails/";
    private static final String VIDEO_ARCHIVE_THUMBNAIL_PREFIX = "video-thumbnails/";
    private static final String ARCHIVE_FORMAT_VERSION = "1";
    private static final String ARCHIVE_PROPERTY_FORMAT_VERSION = "formatVersion";
    private static final String ARCHIVE_PROPERTY_CACHE_VERSION = "cacheVersion";
    private static final String ARCHIVE_PROPERTY_CACHE_TYPE = "cacheType";
    private static final String ARCHIVE_PROPERTY_CREATED_AT = "createdAtEpochMillis";
    private static final String ARCHIVE_PROPERTY_THUMBNAIL_COUNT = "thumbnailCount";
    private static final Pattern THUMBNAIL_FILE_PATTERN =
            Pattern.compile("[0-9a-f]{64}\\.thumb");
    private static final long MAX_IMPORTED_THUMBNAIL_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_MANIFEST_BYTES = 64L * 1024L;
    private static final long MAX_IMPORTED_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int MAX_IMPORTED_ENTRY_COUNT = 1_000_000;
    private static final long DISK_TRIM_COALESCE_DELAY_MS = 750L;
    private static final int MEDIA_NONE = 0;
    private static final int MEDIA_IMAGE = 1;
    private static final int MEDIA_VIDEO = 2;
    private static final int MAX_THUMBNAIL_DIMENSION = 1280;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int REMOTE_MEDIA_READ_AHEAD_BYTES = 4 * 1024 * 1024;
    private static final int REMOTE_MEDIA_MAX_FETCH_BYTES = 8 * 1024 * 1024;
    private static final int REMOTE_MEDIA_CACHED_RANGE_COUNT = 3;
    private static final long REMOTE_MEDIA_RANGE_TIMEOUT_MS = 45_000L;
    private static final long FAILURE_CACHE_DURATION_MS = 12L * 60L * 60L * 1000L;
    private static final double VIDEO_FRAME_BASE_FRACTION = 1.0d / 3.0d;
    private static final double VIDEO_FRAME_RANDOM_OFFSET_FRACTION = 0.08d;
    private static final int REQUEST_LOCK_COUNT = 32;
    private static final int THUMBNAIL_WORKER_COUNT = 2;
    private static final long IMAGE_DOWNLOAD_PROGRESS_BYTES = 8L * 1024L * 1024L;
    private static final long IMAGE_DOWNLOAD_PROGRESS_INTERVAL_MS = 5_000L;
    private static final String THUMBNAIL_LOG_TITLE = "Thumbnail diagnostics";

    private static volatile ThumbnailRepository instance;

    private final Context context;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final ThreadPoolExecutor diagnosticLogExecutor;
    private final Handler mainHandler;
    private final File thumbnailRootDirectory;
    private final File workRootDirectory;
    private final CacheStore imageCache;
    private final CacheStore videoCache;
    private final LruCache<String, Bitmap> imageMemoryCache;
    private final LruCache<String, Bitmap> videoMemoryCache;
    private final Map<String, List<LoadRequest>> inFlight;
    private final Object inFlightLock;
    private final CountDownLatch cacheInitializationLatch;
    private final Object[] requestLocks;
    private final Random random;
    private final AtomicLong diagnosticSequence;
    private final AtomicInteger activeGenerations;
    private final SharedPreferences.OnSharedPreferenceChangeListener thumbnailPreferenceListener;

    private final class CacheStore {
        private final CacheType type;
        private final int mediaKind;
        private final File directory;
        private final File workDirectory;
        private final String cacheVersion;
        private final String statePreferencesName;
        private final String archiveThumbnailPrefix;
        private final int maxPreferenceKeyResource;
        private final int targetPreferenceKeyResource;
        private final int defaultMaxResource;
        private final int defaultTargetResource;
        private final Object diskCacheLock = new Object();
        private final AtomicLong cacheGeneration = new AtomicLong();
        private final AtomicLong trimRequestGeneration = new AtomicLong();
        private final AtomicBoolean trimScheduled = new AtomicBoolean();
        private final AtomicLong statisticsMutationGeneration = new AtomicLong();
        private final AtomicBoolean statisticsRefreshScheduled = new AtomicBoolean();
        private final Object statisticsLock = new Object();
        private final List<CacheStatisticsCallback> pendingStatisticsCallbacks = new ArrayList<>();
        @Nullable
        private volatile CacheStatistics cachedStatistics;
        private volatile long publishedStatisticsGeneration = -1L;

        private CacheStore(CacheType type, int mediaKind, File directory, File workDirectory,
                           String cacheVersion, String statePreferencesName,
                           String archiveThumbnailPrefix, int maxPreferenceKeyResource,
                           int targetPreferenceKeyResource, int defaultMaxResource,
                           int defaultTargetResource) {
            this.type = type;
            this.mediaKind = mediaKind;
            this.directory = directory;
            this.workDirectory = workDirectory;
            this.cacheVersion = cacheVersion;
            this.statePreferencesName = statePreferencesName;
            this.archiveThumbnailPrefix = archiveThumbnailPrefix;
            this.maxPreferenceKeyResource = maxPreferenceKeyResource;
            this.targetPreferenceKeyResource = targetPreferenceKeyResource;
            this.defaultMaxResource = defaultMaxResource;
            this.defaultTargetResource = defaultTargetResource;
            this.cachedStatistics = loadPersistedCacheStatistics(this);
        }
    }

    private ThumbnailRepository(Context context) {
        this.context = context.getApplicationContext();
        AtomicLong workerNumber = new AtomicLong();
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                THUMBNAIL_WORKER_COUNT,
                runnable -> new Thread(
                        runnable,
                        "thumbnail-worker-" + workerNumber.incrementAndGet()));
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
        this.diagnosticLogExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> new Thread(runnable, "thumbnail-diagnostic-log"),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.thumbnailRootDirectory = this.context.getDir(
                THUMBNAIL_DIRECTORY_NAME, Context.MODE_PRIVATE);
        this.workRootDirectory = new File(this.context.getCacheDir(), WORK_DIRECTORY_NAME);
        this.imageCache = new CacheStore(
                CacheType.IMAGE,
                MEDIA_IMAGE,
                new File(thumbnailRootDirectory, IMAGE_THUMBNAIL_DIRECTORY_NAME),
                new File(workRootDirectory, IMAGE_WORK_DIRECTORY_NAME),
                IMAGE_CACHE_VERSION,
                IMAGE_CACHE_STATE_PREFERENCES,
                IMAGE_ARCHIVE_THUMBNAIL_PREFIX,
                R.string.pref_key_thumbnail_image_disk_cache_max_mb,
                R.string.pref_key_thumbnail_image_disk_cache_trim_target_mb,
                R.integer.default_thumbnail_image_disk_cache_max_mb,
                R.integer.default_thumbnail_image_disk_cache_trim_target_mb);
        this.videoCache = new CacheStore(
                CacheType.VIDEO,
                MEDIA_VIDEO,
                new File(thumbnailRootDirectory, VIDEO_THUMBNAIL_DIRECTORY_NAME),
                new File(workRootDirectory, VIDEO_WORK_DIRECTORY_NAME),
                VIDEO_CACHE_VERSION,
                VIDEO_CACHE_STATE_PREFERENCES,
                VIDEO_ARCHIVE_THUMBNAIL_PREFIX,
                R.string.pref_key_thumbnail_video_disk_cache_max_mb,
                R.string.pref_key_thumbnail_video_disk_cache_trim_target_mb,
                R.integer.default_thumbnail_video_disk_cache_max_mb,
                R.integer.default_thumbnail_video_disk_cache_trim_target_mb);
        this.inFlight = new HashMap<>();
        this.inFlightLock = new Object();
        this.cacheInitializationLatch = new CountDownLatch(1);
        this.requestLocks = new Object[REQUEST_LOCK_COUNT];
        for (int index = 0; index < requestLocks.length; index++) {
            requestLocks[index] = new Object();
        }
        this.random = new Random();
        this.diagnosticSequence = new AtomicLong();
        this.activeGenerations = new AtomicInteger();
        int memoryClassKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int perTypeCacheSizeKb = Math.max(8 * 1024, memoryClassKb / 16);
        this.imageMemoryCache = createMemoryCache(perTypeCacheSizeKb);
        this.videoMemoryCache = createMemoryCache(perTypeCacheSizeKb);

        String imageEnabledKey = this.context.getString(
                R.string.pref_key_thumbnail_image_enabled);
        String videoEnabledKey = this.context.getString(
                R.string.pref_key_thumbnail_video_enabled);
        String imageCacheLimitKey = this.context.getString(
                R.string.pref_key_thumbnail_image_disk_cache_max_mb);
        String imageCacheTargetKey = this.context.getString(
                R.string.pref_key_thumbnail_image_disk_cache_trim_target_mb);
        String videoCacheLimitKey = this.context.getString(
                R.string.pref_key_thumbnail_video_disk_cache_max_mb);
        String videoCacheTargetKey = this.context.getString(
                R.string.pref_key_thumbnail_video_disk_cache_trim_target_mb);
        this.thumbnailPreferenceListener = (preferences, key) -> {
            if (imageEnabledKey.equals(key) && !areImageThumbnailsEnabled()) {
                imageCache.cacheGeneration.incrementAndGet();
                imageMemoryCache.evictAll();
            }
            if (videoEnabledKey.equals(key) && !areVideoThumbnailsEnabled()) {
                videoCache.cacheGeneration.incrementAndGet();
                videoMemoryCache.evictAll();
            }
            if (imageCacheLimitKey.equals(key) || imageCacheTargetKey.equals(key)) {
                requestDiskCacheTrim(imageCache, 0L);
            }
            if (videoCacheLimitKey.equals(key) || videoCacheTargetKey.equals(key)) {
                requestDiskCacheTrim(videoCache, 0L);
            }
        };
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this.context);
        preferences.registerOnSharedPreferenceChangeListener(thumbnailPreferenceListener);
        maintenanceExecutor.execute(() -> {
            try {
                initializeCacheDirectories();
                cleanupWorkDirectory(imageCache);
                cleanupWorkDirectory(videoCache);
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unable to initialize thumbnail caches", error);
            } finally {
                cacheInitializationLatch.countDown();
            }
            try {
                cleanupExpiredFailureMarkers(imageCache);
                cleanupExpiredFailureMarkers(videoCache);
                initializeCacheMaintenance(imageCache);
                initializeCacheMaintenance(videoCache);
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unable to initialize thumbnail cache maintenance", error);
            }
        });
    }

    private LruCache<String, Bitmap> createMemoryCache(int cacheSizeKb) {
        return new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }
        };
    }

    private void initializeCacheMaintenance(CacheStore store) {
        CacheStatistics snapshot = store.cachedStatistics;
        if (snapshot == null || snapshot.totalBytes > getDiskCacheMaxBytes(store)) {
            trimDiskCache(store);
        }
    }

    public static ThumbnailRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (ThumbnailRepository.class) {
                if (instance == null) {
                    instance = new ThumbnailRepository(context);
                }
            }
        }
        return instance;
    }

    public boolean areImageThumbnailsEnabled() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String key = context.getString(R.string.pref_key_thumbnail_image_enabled);
        boolean defaultValue = context.getResources().getBoolean(
                R.bool.default_thumbnail_image_enabled);
        return preferences.getBoolean(key, defaultValue);
    }

    public boolean areVideoThumbnailsEnabled() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String key = context.getString(R.string.pref_key_thumbnail_video_enabled);
        boolean defaultValue = context.getResources().getBoolean(
                R.bool.default_thumbnail_video_enabled);
        return preferences.getBoolean(key, defaultValue);
    }

    private boolean isThumbnailEnabled(CacheStore store) {
        return store.type == CacheType.VIDEO
                ? areVideoThumbnailsEnabled()
                : areImageThumbnailsEnabled();
    }

    public boolean isImageThumbnailCandidate(FileItem item) {
        return item != null
                && !item.isDir()
                && getMediaKind(item) == MEDIA_IMAGE;
    }

    public boolean isVideoThumbnailCandidate(FileItem item) {
        return item != null
                && !item.isDir()
                && getMediaKind(item) == MEDIA_VIDEO;
    }

    public boolean supportsThumbnail(FileItem item) {
        if (isImageThumbnailCandidate(item)) {
            return areImageThumbnailsEnabled();
        }
        if (isVideoThumbnailCandidate(item)) {
            return areVideoThumbnailsEnabled();
        }
        return false;
    }

    public boolean supportsVideoThumbnail(FileItem item) {
        return isVideoThumbnailCandidate(item) && areVideoThumbnailsEnabled();
    }

    /**
     * Clears only the selected media cache. The other media type keeps its decoded thumbnails,
     * persistent files, in-flight generation and statistics unchanged.
     */
    public void clearCache(CacheType type, @Nullable Runnable completion) {
        CacheStore store = getCacheStore(type);
        store.cacheGeneration.incrementAndGet();
        maintenanceExecutor.execute(() -> {
            if (!awaitCacheInitialization()) {
                if (completion != null) {
                    mainHandler.post(completion);
                }
                return;
            }
            CacheStatistics statistics;
            long statisticsGeneration;
            synchronized (store.diskCacheLock) {
                getMemoryCache(store).evictAll();
                deleteDirectoryContents(store.directory);
                ensureDirectory(store.directory);
                writeCacheVersionMarkerLocked(store);
                deleteDirectoryContents(store.workDirectory);
                ensureDirectory(store.workDirectory);
                store.statisticsMutationGeneration.incrementAndGet();
                statisticsGeneration = store.statisticsMutationGeneration.get();
                statistics = calculateCacheStatistics(store);
            }
            publishCacheStatistics(store, statistics, statisticsGeneration);
            if (completion != null) {
                mainHandler.post(completion);
            }
        });
    }

    /**
     * Counts persistent thumbnail files and their total on-disk size for one media type. Negative
     * cache markers and temporary source/import files are intentionally excluded.
     */
    public void getCacheStatistics(CacheType type, CacheStatisticsCallback callback) {
        if (callback == null) {
            return;
        }
        CacheStore store = getCacheStore(type);
        CacheStatistics snapshot = store.cachedStatistics;
        boolean needsRefresh = snapshot == null
                || store.publishedStatisticsGeneration
                != store.statisticsMutationGeneration.get();
        if (snapshot != null) {
            mainHandler.post(() -> callback.onCacheStatisticsLoaded(snapshot));
        }
        if (needsRefresh) {
            synchronized (store.statisticsLock) {
                store.pendingStatisticsCallbacks.add(callback);
            }
            requestCacheStatisticsRefresh(store);
        }
    }

    public void exportCache(CacheType type, Uri destination, CacheExportCallback callback) {
        if (destination == null || callback == null) {
            return;
        }
        CacheStore store = getCacheStore(type);
        maintenanceExecutor.execute(() -> {
            CacheExportResult result;
            try {
                result = exportCacheInternal(store, destination);
            } catch (IOException | RuntimeException error) {
                FLog.e(TAG, "Unable to export " + store.type.getArchiveName()
                        + " thumbnail cache", error);
                result = new CacheExportResult(false, 0, 0L,
                        CacheArchiveError.IO_ERROR);
            }
            CacheExportResult callbackResult = result;
            mainHandler.post(() -> callback.onCacheExportFinished(callbackResult));
        });
    }

    public void importCache(CacheType type, Uri source, CacheImportCallback callback) {
        if (source == null || callback == null) {
            return;
        }
        CacheStore store = getCacheStore(type);
        maintenanceExecutor.execute(() -> {
            CacheImportResult result;
            try {
                result = importCacheInternal(store, source);
            } catch (ZipException error) {
                FLog.e(TAG, "Invalid " + store.type.getArchiveName()
                        + " thumbnail cache archive", error);
                result = new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.INVALID_ARCHIVE);
            } catch (IOException | RuntimeException error) {
                FLog.e(TAG, "Unable to import " + store.type.getArchiveName()
                        + " thumbnail cache", error);
                result = new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.IO_ERROR);
            }
            try {
                trimDiskCache(store);
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unable to trim " + store.type.getArchiveName()
                        + " thumbnail cache after import", error);
            }
            CacheImportResult callbackResult = result;
            mainHandler.post(() -> callback.onCacheImportFinished(callbackResult));
        });
    }

    private CacheExportResult exportCacheInternal(CacheStore store, Uri destination) throws IOException {
        File[] files;
        synchronized (store.diskCacheLock) {
            files = store.directory.listFiles(
                    (dir, name) -> THUMBNAIL_FILE_PATTERN.matcher(name).matches());
        }
        if (files == null) {
            files = new File[0];
        }
        Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));

        OutputStream rawOutput = context.getContentResolver().openOutputStream(destination, "w");
        if (rawOutput == null) {
            throw new IOException("Unable to open thumbnail cache export destination");
        }

        int exportedCount = 0;
        long exportedBytes = 0L;
        try (OutputStream output = rawOutput;
             ZipOutputStream zipOutput = new ZipOutputStream(
                     new BufferedOutputStream(output, 128 * 1024))) {
            Properties manifest = new Properties();
            manifest.setProperty(ARCHIVE_PROPERTY_FORMAT_VERSION, ARCHIVE_FORMAT_VERSION);
            manifest.setProperty(ARCHIVE_PROPERTY_CACHE_VERSION, store.cacheVersion);
            manifest.setProperty(ARCHIVE_PROPERTY_CACHE_TYPE, store.type.getArchiveName());
            manifest.setProperty(ARCHIVE_PROPERTY_CREATED_AT,
                    Long.toString(System.currentTimeMillis()));
            manifest.setProperty(ARCHIVE_PROPERTY_THUMBNAIL_COUNT,
                    Integer.toString(files.length));

            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            manifest.store(manifestBytes, "Round Sync thumbnail cache archive");
            ZipEntry manifestEntry = new ZipEntry(ARCHIVE_MANIFEST_ENTRY);
            manifestEntry.setTime(System.currentTimeMillis());
            zipOutput.putNextEntry(manifestEntry);
            manifestBytes.writeTo(zipOutput);
            zipOutput.closeEntry();

            byte[] buffer = new byte[128 * 1024];
            for (File file : files) {
                String fileName = file.getName();
                if (!THUMBNAIL_FILE_PATTERN.matcher(fileName).matches()) {
                    continue;
                }
                String requestKey = fileName.substring(0, fileName.length() - ".thumb".length());
                synchronized (getRequestLock(requestKey)) {
                    long fileLength;
                    long fileTimestamp;
                    FileInputStream rawInput;
                    synchronized (store.diskCacheLock) {
                        if (!file.isFile() || file.length() <= 0L) {
                            continue;
                        }
                        fileLength = file.length();
                        fileTimestamp = file.lastModified();
                        rawInput = new FileInputStream(file);
                    }
                    ZipEntry thumbnailEntry = new ZipEntry(
                            store.archiveThumbnailPrefix + fileName);
                    if (fileTimestamp > 0L) {
                        thumbnailEntry.setTime(fileTimestamp);
                    }
                    try (InputStream input = new BufferedInputStream(
                            rawInput, 128 * 1024)) {
                        zipOutput.putNextEntry(thumbnailEntry);
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read > 0) {
                                zipOutput.write(buffer, 0, read);
                            }
                        }
                    }
                    zipOutput.closeEntry();
                    exportedCount++;
                    exportedBytes = saturatedAdd(exportedBytes, fileLength);
                }
            }
            zipOutput.finish();
        }
        return new CacheExportResult(true, exportedCount, exportedBytes,
                CacheArchiveError.NONE);
    }

    private CacheImportResult importCacheInternal(CacheStore store, Uri source) throws IOException {
        InputStream rawInput = context.getContentResolver().openInputStream(source);
        if (rawInput == null) {
            throw new IOException("Unable to open thumbnail cache archive");
        }

        int addedCount = 0;
        int replacedCount = 0;
        int skippedCount = 0;
        int entryCount = 0;
        long importedBytes = 0L;
        long processedArchiveBytes = 0L;
        boolean importActivated = false;

        try (InputStream input = rawInput;
             ZipInputStream zipInput = new ZipInputStream(
                     new BufferedInputStream(input, 128 * 1024))) {
            ZipEntry entry = nextNonDirectoryEntry(zipInput);
            if (entry == null || !ARCHIVE_MANIFEST_ENTRY.equals(entry.getName())) {
                return new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.INVALID_ARCHIVE);
            }

            Properties manifest = new Properties();
            byte[] manifestData;
            try {
                manifestData = readZipEntryBytes(zipInput, MAX_ARCHIVE_MANIFEST_BYTES);
            } catch (ArchiveEntryTooLargeException error) {
                return new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.INVALID_ARCHIVE);
            }
            try {
                manifest.load(new ByteArrayInputStream(manifestData));
            } catch (IllegalArgumentException error) {
                return new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.INVALID_ARCHIVE);
            }
            zipInput.closeEntry();
            if (!ARCHIVE_FORMAT_VERSION.equals(
                    manifest.getProperty(ARCHIVE_PROPERTY_FORMAT_VERSION))) {
                return new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.INVALID_ARCHIVE);
            }
            if (!store.cacheVersion.equals(
                    manifest.getProperty(ARCHIVE_PROPERTY_CACHE_VERSION))
                    || !store.type.getArchiveName().equals(
                    manifest.getProperty(ARCHIVE_PROPERTY_CACHE_TYPE))) {
                return new CacheImportResult(false, 0, 0, 0, 0L,
                        CacheArchiveError.INCOMPATIBLE_CACHE_VERSION);
            }

            while ((entry = zipInput.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInput.closeEntry();
                    continue;
                }
                entryCount++;
                if (entryCount > MAX_IMPORTED_ENTRY_COUNT) {
                    return new CacheImportResult(false, addedCount, replacedCount,
                            skippedCount, importedBytes, CacheArchiveError.INVALID_ARCHIVE);
                }

                String fileName = getArchiveThumbnailFileName(store, entry.getName());
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_IMPORTED_THUMBNAIL_BYTES) {
                    return new CacheImportResult(false, addedCount, replacedCount,
                            skippedCount, importedBytes, CacheArchiveError.INVALID_ARCHIVE);
                }
                if (fileName == null) {
                    long discardedBytes;
                    try {
                        discardedBytes = discardZipEntry(zipInput,
                                MAX_IMPORTED_THUMBNAIL_BYTES);
                    } catch (ArchiveEntryTooLargeException error) {
                        return new CacheImportResult(false, addedCount, replacedCount,
                                skippedCount, importedBytes, CacheArchiveError.INVALID_ARCHIVE);
                    }
                    processedArchiveBytes = saturatedAdd(
                            processedArchiveBytes, discardedBytes);
                    if (processedArchiveBytes > MAX_IMPORTED_ARCHIVE_BYTES) {
                        return new CacheImportResult(false, addedCount, replacedCount,
                                skippedCount, importedBytes, CacheArchiveError.INVALID_ARCHIVE);
                    }
                    skippedCount++;
                    zipInput.closeEntry();
                    continue;
                }

                File temporary = File.createTempFile("thumbnail_import_", ".tmp", store.workDirectory);
                try {
                    long copiedBytes;
                    try {
                        copiedBytes = copyZipEntryToFile(zipInput, temporary,
                                MAX_IMPORTED_THUMBNAIL_BYTES);
                    } catch (ArchiveEntryTooLargeException error) {
                        return new CacheImportResult(false, addedCount, replacedCount,
                                skippedCount, importedBytes, CacheArchiveError.INVALID_ARCHIVE);
                    }
                    zipInput.closeEntry();
                    processedArchiveBytes = saturatedAdd(processedArchiveBytes, copiedBytes);
                    if (processedArchiveBytes > MAX_IMPORTED_ARCHIVE_BYTES) {
                        return new CacheImportResult(false, addedCount, replacedCount,
                                skippedCount, importedBytes, CacheArchiveError.INVALID_ARCHIVE);
                    }

                    if (copiedBytes <= 0L || !isUsableThumbnail(temporary)) {
                        skippedCount++;
                        continue;
                    }

                    String requestKey = fileName.substring(
                            0, fileName.length() - ".thumb".length());
                    File destination = new File(store.directory, fileName);
                    if (!importActivated) {
                        store.cacheGeneration.incrementAndGet();
                        getMemoryCache(store).evictAll();
                        importActivated = true;
                    }
                    boolean replaced;
                    synchronized (getRequestLock(requestKey)) {
                        synchronized (store.diskCacheLock) {
                            replaced = destination.isFile();
                            replaceImportedThumbnailLocked(store, temporary, destination);
                            long archivedTimestamp = entry.getTime();
                            //noinspection ResultOfMethodCallIgnored
                            destination.setLastModified(archivedTimestamp > 0L
                                    ? archivedTimestamp : System.currentTimeMillis());
                            store.statisticsMutationGeneration.incrementAndGet();
                        }
                        evictMemoryEntries(store, requestKey);
                    }
                    importedBytes = saturatedAdd(importedBytes, copiedBytes);
                    if (replaced) {
                        replacedCount++;
                    } else {
                        addedCount++;
                    }
                } finally {
                    deleteQuietly(temporary);
                }
            }
        }

        if (addedCount + replacedCount == 0) {
            return new CacheImportResult(false, 0, 0, skippedCount, importedBytes,
                    CacheArchiveError.EMPTY_ARCHIVE);
        }
        return new CacheImportResult(true, addedCount, replacedCount, skippedCount,
                importedBytes, CacheArchiveError.NONE);
    }


    private int getMediaKind(FileItem item) {
        String mimeType = item.getMimeType();
        if (mimeType != null) {
            String normalizedMime = mimeType.toLowerCase(Locale.US);
            if (normalizedMime.startsWith("image/")) {
                return MEDIA_IMAGE;
            }
            if (normalizedMime.startsWith("video/")) {
                return MEDIA_VIDEO;
            }
        }

        String name = item.getName();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return MEDIA_NONE;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.US);
        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "webp":
            case "bmp":
            case "heic":
            case "heif":
            case "tif":
            case "tiff":
            case "avif":
            case "dng":
                return MEDIA_IMAGE;
            case "mp4":
            case "m4v":
            case "mkv":
            case "webm":
            case "avi":
            case "mov":
            case "wmv":
            case "flv":
            case "3gp":
            case "3g2":
            case "ts":
            case "mts":
            case "m2ts":
            case "mpg":
            case "mpeg":
            case "vob":
            case "ogv":
                return MEDIA_VIDEO;
            default:
                return MEDIA_NONE;
        }
    }

    private CacheStore getCacheStore(CacheType type) {
        if (type == CacheType.VIDEO) {
            return videoCache;
        }
        return imageCache;
    }

    @Nullable
    private CacheStore getCacheStore(FileItem item) {
        int mediaKind = getMediaKind(item);
        if (mediaKind == MEDIA_IMAGE) {
            return imageCache;
        }
        if (mediaKind == MEDIA_VIDEO) {
            return videoCache;
        }
        return null;
    }

    private LruCache<String, Bitmap> getMemoryCache(CacheStore store) {
        return store.type == CacheType.VIDEO ? videoMemoryCache : imageMemoryCache;
    }

    public String getRequestKey(FileItem item) {
        CacheStore store = getCacheStore(item);
        String cacheVersion = store == null ? "unsupported" : store.cacheVersion;
        return hash(cacheVersion + '|' + item.getRemote().getName() + '|'
                + item.getRemote().getType() + '|' + item.getPath() + '|'
                + item.getSize() + '|' + item.getModTime());
    }

    /**
     * Ensures that a thumbnail exists in the selected persistent cache without decoding a display
     * bitmap. This is intended for recursive folder pre-generation jobs. The selected media type,
     * its global enable switch, cache generation, failure marker and disk lock are all enforced;
     * the other media cache is never touched.
     */
    public PreGenerationResult preGenerateThumbnail(FileItem item, CacheType type) {
        if (item == null || item.isDir()) {
            return PreGenerationResult.UNSUPPORTED;
        }

        CacheStore store = getCacheStore(item);
        if (store == null || store.type != type) {
            return PreGenerationResult.UNSUPPORTED;
        }
        if (!isThumbnailEnabled(store)) {
            return PreGenerationResult.DISABLED;
        }
        if (!awaitCacheInitialization()) {
            return PreGenerationResult.FAILED;
        }

        String requestKey = getRequestKey(item);
        synchronized (getRequestLock(requestKey)) {
            long generation = store.cacheGeneration.get();
            if (!isThumbnailEnabled(store)) {
                return PreGenerationResult.DISABLED;
            }

            File thumbnail = new File(store.directory, requestKey + ".thumb");
            File failure = new File(store.directory, requestKey + ".failed");
            try {
                if (isUsableThumbnail(thumbnail)) {
                    // A bulk pre-generation pass must not make every existing item artificially
                    // recent in the disk LRU. Only interactive loads update lastModified.
                    //noinspection ResultOfMethodCallIgnored
                    failure.delete();
                    return PreGenerationResult.ALREADY_CACHED;
                }

                deleteCachedThumbnail(store, thumbnail);
                if (isRecentFailure(failure)) {
                    return PreGenerationResult.RECENT_FAILURE;
                }
                //noinspection ResultOfMethodCallIgnored
                failure.delete();

                if (generateThumbnail(item, store, thumbnail, generation, "recursive")
                        && isUsableThumbnail(thumbnail)) {
                    return PreGenerationResult.GENERATED;
                }

                deleteCachedThumbnail(store, thumbnail);
                if (!isThumbnailEnabled(store)) {
                    return PreGenerationResult.DISABLED;
                }
                if (generation == store.cacheGeneration.get()) {
                    markFailure(failure);
                }
                return PreGenerationResult.FAILED;
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unable to pre-generate thumbnail for " + item.getPath(), error);
                deleteCachedThumbnail(store, thumbnail);
                if (generation == store.cacheGeneration.get() && isThumbnailEnabled(store)) {
                    markFailure(failure);
                }
                return PreGenerationResult.FAILED;
            }
        }
    }

    /**
     * Forces a video thumbnail to be generated again. Only the video cache entry and the video
     * memory cache are touched.
     */
    public void regenerateVideoThumbnail(FileItem item, int requestedSizePx, Callback callback) {
        if (!supportsVideoThumbnail(item)) {
            mainHandler.post(() -> callback.onThumbnailLoaded(null));
            return;
        }

        CacheStore store = videoCache;
        LruCache<String, Bitmap> memoryCache = getMemoryCache(store);
        int bucket = Math.max(64, ((requestedSizePx + 63) / 64) * 64);
        String requestKey = getRequestKey(item);
        String memoryKey = requestKey + '@' + bucket;
        logThumbnailEvent(false, "QUEUED manual-regenerate", item, store,
                "request=" + shortRequestKey(requestKey));
        executor.execute(() -> {
            Bitmap bitmap = null;
            if (!awaitCacheInitialization()) {
                mainHandler.post(() -> callback.onThumbnailLoaded(null));
                return;
            }
            Object requestLock = getRequestLock(requestKey);
            logThumbnailEvent(false, "WAIT LOCK manual-regenerate", item, store,
                    "request=" + shortRequestKey(requestKey));
            synchronized (requestLock) {
                logThumbnailEvent(false, "LOCK ACQUIRED manual-regenerate", item, store,
                        "request=" + shortRequestKey(requestKey));
                long generation = store.cacheGeneration.get();
                File thumbnail = new File(store.directory, requestKey + ".thumb");
                File failure = new File(store.directory, requestKey + ".failed");

                evictMemoryEntries(store, requestKey);
                deleteCachedThumbnail(store, thumbnail);
                deleteQuietly(failure);

                if (generation == store.cacheGeneration.get()
                        && generateThumbnail(item, store, thumbnail, generation, "manual-regenerate")
                        && isUsableThumbnail(thumbnail)) {
                    try {
                        bitmap = decodeForDisplay(thumbnail, bucket);
                    } catch (RuntimeException e) {
                        FLog.e(TAG, "Unable to decode regenerated thumbnail "
                                + thumbnail.getName(), e);
                    }
                    if (bitmap != null) {
                        memoryCache.put(memoryKey, bitmap);
                    } else if (generation == store.cacheGeneration.get()) {
                        deleteCachedThumbnail(store, thumbnail);
                        markFailure(failure);
                    }
                } else if (generation == store.cacheGeneration.get()) {
                    deleteCachedThumbnail(store, thumbnail);
                    markFailure(failure);
                }
            }

            Bitmap result = bitmap;
            mainHandler.post(() -> callback.onThumbnailLoaded(result));
        });
    }

    public Cancellable load(FileItem item, int requestedSizePx, Callback callback) {
        if (!supportsThumbnail(item)) {
            mainHandler.post(() -> callback.onThumbnailLoaded(null));
            return NO_OP_CANCELLABLE;
        }

        CacheStore store = getCacheStore(item);
        if (store == null) {
            mainHandler.post(() -> callback.onThumbnailLoaded(null));
            return NO_OP_CANCELLABLE;
        }
        LruCache<String, Bitmap> memoryCache = getMemoryCache(store);
        int bucket = Math.max(64, ((requestedSizePx + 63) / 64) * 64);
        String requestKey = getRequestKey(item);
        String memoryKey = requestKey + '@' + bucket;
        Bitmap memoryBitmap = memoryCache.get(memoryKey);
        if (memoryBitmap != null && !memoryBitmap.isRecycled()) {
            mainHandler.post(() -> callback.onThumbnailLoaded(memoryBitmap));
            return NO_OP_CANCELLABLE;
        }

        LoadRequest request = new LoadRequest(bucket, memoryKey, callback);
        boolean startWork = false;
        synchronized (inFlightLock) {
            List<LoadRequest> requests = inFlight.get(requestKey);
            if (requests == null) {
                requests = new ArrayList<>();
                inFlight.put(requestKey, requests);
                startWork = true;
            }
            requests.add(request);
        }

        if (startWork) {
            long requestedGeneration = cacheInitializationLatch.getCount() == 0L
                    ? store.cacheGeneration.get() : -1L;
            logThumbnailEvent(false, "QUEUED interactive", item, store,
                    "request=" + shortRequestKey(requestKey));
            executor.execute(() -> loadOrGenerate(
                    item, store, requestKey, requestedGeneration));
        }
        return request;
    }

    private void loadOrGenerate(FileItem item, CacheStore store, String requestKey,
                                long requestedGeneration) {
        logThumbnailEvent(false, "DEQUEUED interactive", item, store,
                "request=" + shortRequestKey(requestKey) + ", awaiting cache initialization");
        if (!awaitCacheInitialization()) {
            logThumbnailEvent(true, "ABORT interactive", item, store,
                    "request=" + shortRequestKey(requestKey)
                            + ", cache initialization wait interrupted");
            completeRequestsWithNull(requestKey);
            return;
        }
        long generation = requestedGeneration >= 0L
                ? requestedGeneration : store.cacheGeneration.get();
        Object requestLock = getRequestLock(requestKey);
        logThumbnailEvent(false, "WAIT LOCK interactive", item, store,
                "request=" + shortRequestKey(requestKey));
        synchronized (requestLock) {
            logThumbnailEvent(false, "LOCK ACQUIRED interactive", item, store,
                    "request=" + shortRequestKey(requestKey));
            if (generation != store.cacheGeneration.get()) {
                completeRequestsWithNull(requestKey);
                return;
            }
            if (discardIfNoActiveRequests(requestKey)) {
                return;
            }
            if (!isThumbnailEnabled(store)) {
                completeRequestsWithNull(requestKey);
                return;
            }

            File thumbnail = new File(store.directory, requestKey + ".thumb");
            File failure = new File(store.directory, requestKey + ".failed");
            File resolvedThumbnail = null;

            try {
                if (isUsableThumbnail(thumbnail)) {
                    logThumbnailEvent(false, "CACHE HIT interactive", item, store,
                            "request=" + shortRequestKey(requestKey));
                    // lastModified doubles as the disk LRU access timestamp.
                    //noinspection ResultOfMethodCallIgnored
                    thumbnail.setLastModified(System.currentTimeMillis());
                    //noinspection ResultOfMethodCallIgnored
                    failure.delete();
                    resolvedThumbnail = thumbnail;
                } else {
                    deleteCachedThumbnail(store, thumbnail);
                    if (!isRecentFailure(failure)) {
                        //noinspection ResultOfMethodCallIgnored
                        failure.delete();
                        if (generateThumbnail(item, store, thumbnail, generation, "interactive")
                                && isUsableThumbnail(thumbnail)) {
                            resolvedThumbnail = thumbnail;
                        } else {
                            deleteCachedThumbnail(store, thumbnail);
                            if (generation == store.cacheGeneration.get()) {
                                markFailure(failure);
                            }
                        }
                    } else {
                        logThumbnailEvent(false, "SKIP interactive", item, store,
                                "request=" + shortRequestKey(requestKey)
                                        + ", recent failure marker");
                    }
                }
            } catch (RuntimeException e) {
                FLog.e(TAG, "Unable to load cached thumbnail for " + item.getPath(), e);
                deleteCachedThumbnail(store, thumbnail);
                if (generation == store.cacheGeneration.get()) {
                    markFailure(failure);
                }
            }

            List<LoadRequest> requests;
            synchronized (inFlightLock) {
                requests = inFlight.remove(requestKey);
            }
            if (requests == null) {
                return;
            }

            boolean generationIsCurrent = generation == store.cacheGeneration.get();
            boolean thumbnailsEnabled = generationIsCurrent && isThumbnailEnabled(store);
            Map<Integer, Bitmap> decodedByBucket = new HashMap<>();
            LruCache<String, Bitmap> memoryCache = getMemoryCache(store);
            for (LoadRequest request : requests) {
                if (request.isCancelled()) {
                    continue;
                }
                Bitmap bitmap = !thumbnailsEnabled || resolvedThumbnail == null
                        ? null
                        : decodedByBucket.get(request.requestedSizePx);
                if (bitmap == null && thumbnailsEnabled && resolvedThumbnail != null) {
                    try {
                        bitmap = decodeForDisplay(resolvedThumbnail, request.requestedSizePx);
                    } catch (RuntimeException e) {
                        FLog.e(TAG, "Unable to decode cached thumbnail "
                                + resolvedThumbnail.getName(), e);
                    }
                    if (bitmap != null) {
                        decodedByBucket.put(request.requestedSizePx, bitmap);
                    }
                }
                if (bitmap != null) {
                    memoryCache.put(request.memoryKey, bitmap);
                }
                Bitmap callbackBitmap = bitmap;
                mainHandler.post(() -> request.callback.onThumbnailLoaded(callbackBitmap));
            }
        }
    }

    private void completeRequestsWithNull(String requestKey) {
        List<LoadRequest> requests;
        synchronized (inFlightLock) {
            requests = inFlight.remove(requestKey);
        }
        if (requests == null) {
            return;
        }
        for (LoadRequest request : requests) {
            if (!request.isCancelled()) {
                mainHandler.post(() -> request.callback.onThumbnailLoaded(null));
            }
        }
    }

    private boolean discardIfNoActiveRequests(String requestKey) {
        synchronized (inFlightLock) {
            List<LoadRequest> requests = inFlight.get(requestKey);
            if (requests != null) {
                for (LoadRequest request : requests) {
                    if (!request.isCancelled()) {
                        return false;
                    }
                }
            }
            inFlight.remove(requestKey);
            return true;
        }
    }

    private boolean generateThumbnail(FileItem item, CacheStore store, File outputFile,
                                      long generation, String origin) {
        int mediaKind = getMediaKind(item);
        if (mediaKind == MEDIA_NONE || mediaKind != store.mediaKind) {
            return false;
        }

        long operationId = diagnosticSequence.incrementAndGet();
        long startedAt = SystemClock.elapsedRealtime();
        int active = activeGenerations.incrementAndGet();
        String outcome = "failed";
        Bitmap bitmap = null;
        File source = null;
        logThumbnailEvent(false, "START #" + operationId + " " + origin, item, store,
                "activeGenerations=" + active);
        try {
            if (mediaKind == MEDIA_IMAGE) {
                long imageSourceLimitBytes = getImageSourceSizeLimitBytes();
                if (item.getSize() > 0 && item.getSize() > imageSourceLimitBytes) {
                    outcome = "source-size-limit";
                    FLog.d(TAG, "Image exceeds thumbnail source limit: %s", item.getPath());
                    logThumbnailEvent(false, "SKIP #" + operationId, item, store,
                            "image exceeds source limit=" + imageSourceLimitBytes + " bytes");
                    return false;
                }
                logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                        "opening/downloading image source");
                source = downloadImageSource(
                        item, imageSourceLimitBytes, store.workDirectory, operationId, store);
                logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                        "decoding downloaded image bytes=" + source.length());
                bitmap = extractImageThumbnail(source);
            } else if (mediaKind == MEDIA_VIDEO) {
                logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                        "starting video extraction");
                bitmap = extractVideoThumbnail(item, operationId, store);
            }

            if (bitmap == null) {
                outcome = "extractor-returned-null";
                return false;
            }
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "writing thumbnail " + bitmap.getWidth() + "x" + bitmap.getHeight());
            synchronized (store.diskCacheLock) {
                if (generation != store.cacheGeneration.get()
                        || !isThumbnailEnabled(store)) {
                    outcome = "cache-generation-changed-or-disabled";
                    return false;
                }
                boolean written = writeBitmapAtomically(bitmap, outputFile);
                if (written) {
                    store.statisticsMutationGeneration.incrementAndGet();
                    requestDiskCacheTrim(store, DISK_TRIM_COALESCE_DELAY_MS);
                    outcome = "generated";
                } else {
                    outcome = "cache-write-failed";
                }
                return written;
            }
        } catch (SourceSizeLimitExceededException e) {
            outcome = "source-size-limit";
            FLog.d(TAG, "Image exceeds thumbnail source limit: %s", item.getPath());
            return false;
        } catch (IOException | RuntimeException e) {
            outcome = e.getClass().getSimpleName() + ": " + safeMessage(e);
            FLog.e(TAG, "Unable to generate thumbnail for " + item.getPath(), e);
            logThumbnailEvent(true, "ERROR #" + operationId, item, store, outcome);
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            deleteQuietly(source);
            int remainingActive = activeGenerations.decrementAndGet();
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            logThumbnailEvent(!"generated".equals(outcome),
                    "FINISH #" + operationId + " " + origin, item, store,
                    "outcome=" + outcome + ", elapsedMs=" + elapsed
                            + ", activeGenerations=" + remainingActive);
        }
    }

    private File downloadImageSource(FileItem item, long maximumBytes, File workDirectory,
                                     long operationId, CacheStore store)
            throws IOException, FileAccessError {
        String suffix = getSafeSuffix(item.getName());
        File source = File.createTempFile("thumbnail_source_", suffix, workDirectory);

        logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                "opening remote image stream");
        try (InputStream rawInput = openImageSource(item);
             BufferedInputStream input = new BufferedInputStream(rawInput, 128 * 1024);
             BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(source), 128 * 1024)) {
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "remote image stream opened");
            byte[] buffer = new byte[128 * 1024];
            long totalBytes = 0L;
            long nextProgressBytes = IMAGE_DOWNLOAD_PROGRESS_BYTES;
            long lastProgressAt = SystemClock.elapsedRealtime();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (totalBytes > maximumBytes - read) {
                    throw new SourceSizeLimitExceededException();
                }
                output.write(buffer, 0, read);
                totalBytes += read;
                long now = SystemClock.elapsedRealtime();
                if (totalBytes >= nextProgressBytes
                        || now - lastProgressAt >= IMAGE_DOWNLOAD_PROGRESS_INTERVAL_MS) {
                    logThumbnailEvent(false, "PROGRESS #" + operationId, item, store,
                            "downloadedBytes=" + totalBytes);
                    nextProgressBytes = totalBytes + IMAGE_DOWNLOAD_PROGRESS_BYTES;
                    lastProgressAt = now;
                }
            }
        } catch (IOException | FileAccessError e) {
            deleteQuietly(source);
            throw e;
        }

        logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                "image download complete bytes=" + source.length());
        if (source.length() == 0) {
            deleteQuietly(source);
            throw new IOException("Remote thumbnail source is empty");
        }
        return source;
    }

    private InputStream openImageSource(FileItem item) throws IOException, FileAccessError {
        if (item.getRemote().getType() == RemoteItem.SAFW) {
            Uri contentUri = SafAccessProvider.getDirectServer(context)
                    .getDocumentUri('/' + item.getPath());
            InputStream input = context.getContentResolver().openInputStream(contentUri);
            if (input == null) {
                throw new IOException("Unable to open SAF source");
            }
            return input;
        }

        Rclone rclone = new Rclone(context);
        String remotePath = rclone.getRemoteFilePath(item.getRemote(), item);
        return rclone.downloadToPipe(remotePath);
    }

    @Nullable
    private Bitmap extractImageThumbnail(File source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight,
                MAX_THUMBNAIL_DIMENSION * 2);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (bitmap == null) {
            return null;
        }

        bitmap = applyImageOrientation(bitmap, source);
        return scaleDown(bitmap, MAX_THUMBNAIL_DIMENSION);
    }

    @Nullable
    private Bitmap extractVideoThumbnail(FileItem item, long operationId, CacheStore store) {
        double targetFraction = getRandomVideoFrameFraction();
        logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                "platform video extractor: opening source");
        Bitmap frame = extractVideoThumbnailWithPlatform(
                item, targetFraction, operationId, store);
        if (frame != null) {
            return frame;
        }

        // The fallback is deliberately format-agnostic. WMV is a common trigger, but Android's
        // platform retriever can also fail for otherwise valid AVI, MOV, MKV, MPEG-TS and other
        // files depending on OS/vendor codec availability and container details.
        FLog.d(TAG, "Platform video thumbnail failed; trying bundled FFmpeg for %s",
                item.getPath());
        logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                "FFmpeg fallback: opening source");
        frame = extractVideoThumbnailWithFfmpeg(
                item, targetFraction, operationId, store);
        if (frame == null) {
            FLog.d(TAG, "Bundled FFmpeg video thumbnail failed for %s", item.getPath());
        }
        return frame;
    }

    @Nullable
    private Bitmap extractVideoThumbnailWithPlatform(
            FileItem item, double targetFraction, long operationId, CacheStore store) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaDataSource mediaDataSource = null;
        try {
            if (item.getRemote().getType() == RemoteItem.SAFW) {
                Uri contentUri = SafAccessProvider.getDirectServer(context)
                        .getDocumentUri('/' + item.getPath());
                retriever.setDataSource(context, contentUri);
            } else {
                Rclone rclone = new Rclone(context);
                String remotePath = rclone.getRemoteFilePath(item.getRemote(), item);
                mediaDataSource = new RcloneRangeMediaDataSource(
                        rclone,
                        remotePath,
                        item.getSize());
                retriever.setDataSource(mediaDataSource);
            }
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "platform video extractor: source opened");

            long durationMs = parseLongMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0L);
            int width = parseIntMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int height = parseIntMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rotation = parseIntMetadata(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            long targetTimeUs = getVideoFrameTimeUs(durationMs, targetFraction);
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "platform video extractor: metadata durationMs=" + durationMs
                            + ", dimensions=" + width + "x" + height
                            + ", targetTimeUs=" + targetTimeUs);

            Bitmap frame;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 0 && height > 0) {
                int[] targetSize = fitInside(width, height, MAX_THUMBNAIL_DIMENSION);
                frame = retriever.getScaledFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetSize[0],
                        targetSize[1]);
            } else {
                frame = retriever.getFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                        "platform video extractor returned no frame");
                return null;
            }
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "platform video extractor returned frame="
                            + frame.getWidth() + "x" + frame.getHeight());
            if (rotation == 90 || rotation == 180 || rotation == 270) {
                frame = rotate(frame, rotation);
            }
            return scaleDown(frame, MAX_THUMBNAIL_DIMENSION);
        } catch (RuntimeException e) {
            FLog.d(TAG, "Platform video preview extraction failed for %s: %s",
                    item.getPath(), e.getMessage());
            logThumbnailEvent(true, "PLATFORM ERROR #" + operationId, item, store,
                    e.getClass().getSimpleName() + ": " + safeMessage(e));
            return null;
        } finally {
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "platform video extractor: releasing source");
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Some platform or vendor retrievers may throw while releasing.
            }

            if (mediaDataSource != null) {
                try {
                    mediaDataSource.close();
                } catch (IOException ignored) {
                    // The retriever has already released its reference.
                }
            }
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "platform video extractor: source released");
        }
    }

    @Nullable
    private Bitmap extractVideoThumbnailWithFfmpeg(
            FileItem item, double targetFraction, long operationId, CacheStore store) {
        if (!FfmpegThumbnailExtractor.isAvailable()) {
            return null;
        }
        try (FfmpegThumbnailExtractor.RandomAccessSource source =
                     openFfmpegRandomAccessSource(item)) {
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "FFmpeg fallback: source opened, extracting frame");
            Bitmap frame = FfmpegThumbnailExtractor.extractFrame(
                    source,
                    targetFraction,
                    MAX_THUMBNAIL_DIMENSION);
            if (frame == null) {
                logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                        "FFmpeg fallback returned no frame");
                return null;
            }
            logThumbnailEvent(false, "STAGE #" + operationId, item, store,
                    "FFmpeg fallback returned frame="
                            + frame.getWidth() + "x" + frame.getHeight());
            return scaleDown(frame, MAX_THUMBNAIL_DIMENSION);
        } catch (IOException | RuntimeException e) {
            FLog.d(TAG, "Unable to open FFmpeg random-access source for %s: %s",
                    item.getPath(), e.getMessage());
            logThumbnailEvent(true, "FFMPEG ERROR #" + operationId, item, store,
                    e.getClass().getSimpleName() + ": " + safeMessage(e));
            return null;
        }
    }

    private FfmpegThumbnailExtractor.RandomAccessSource openFfmpegRandomAccessSource(
            FileItem item) throws IOException {
        if (item.getRemote().getType() == RemoteItem.SAFW) {
            Uri contentUri = SafAccessProvider.getDirectServer(context)
                    .getDocumentUri('/' + item.getPath());
            AssetFileDescriptor descriptor = context.getContentResolver()
                    .openAssetFileDescriptor(contentUri, "r");
            if (descriptor == null) {
                throw new IOException("Unable to open seekable SAF source");
            }
            try {
                return new SeekableContentMediaDataSource(descriptor, item.getSize());
            } catch (IOException | RuntimeException e) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // Preserve the original failure.
                }
                throw e;
            }
        }

        Rclone rclone = new Rclone(context);
        String remotePath = rclone.getRemoteFilePath(item.getRemote(), item);
        return new RcloneRangeMediaDataSource(rclone, remotePath, item.getSize());
    }

    private void logThumbnailEvent(boolean error, String event, FileItem item,
                                   CacheStore store, String details) {
        if (!SyncLog.isThumbnailLoggingEnabled(context)) {
            return;
        }
        String mediaType = store == null
                ? "unknown" : store.type.getArchiveName();
        String remote = item == null || item.getRemote() == null
                ? "unknown" : sanitizeLogValue(item.getRemote().getName());
        String path = item == null ? "unknown" : sanitizeLogValue(item.getPath());
        long size = item == null ? -1L : item.getSize();
        String message = event
                + " | media=" + mediaType
                + " | remote=" + remote
                + " | path=" + path
                + " | size=" + size
                + " | worker=" + Thread.currentThread().getName()
                + " | poolActive=" + executor.getActiveCount() + "/" + THUMBNAIL_WORKER_COUNT
                + " | poolQueued=" + executor.getQueue().size()
                + (details == null || details.isEmpty() ? "" : " | " + details);
        diagnosticLogExecutor.execute(() -> {
            if (error) {
                SyncLog.thumbnailError(context, THUMBNAIL_LOG_TITLE, message);
            } else {
                SyncLog.thumbnailInfo(context, THUMBNAIL_LOG_TITLE, message);
            }
        });
    }

    private static String shortRequestKey(String requestKey) {
        if (requestKey == null || requestKey.length() <= 12) {
            return requestKey == null ? "" : requestKey;
        }
        return requestKey.substring(0, 12);
    }

    private static String sanitizeLogValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "no message" : sanitizeLogValue(message);
    }

    private double getRandomVideoFrameFraction() {
        double randomOffset = (random.nextDouble() * 2.0d - 1.0d)
                * VIDEO_FRAME_RANDOM_OFFSET_FRACTION;
        return VIDEO_FRAME_BASE_FRACTION + randomOffset;
    }

    private long getVideoFrameTimeUs(long durationMs, double targetFraction) {
        if (durationMs <= 0L) {
            return 0L;
        }
        return Math.max(0L, Math.round(durationMs * 1000.0d * targetFraction));
    }

    private Object getRequestLock(String requestKey) {
        int index = (requestKey.hashCode() & Integer.MAX_VALUE) % requestLocks.length;
        return requestLocks[index];
    }

    private void evictMemoryEntries(CacheStore store, String requestKey) {
        LruCache<String, Bitmap> memoryCache = getMemoryCache(store);
        String prefix = requestKey + '@';
        for (String key : memoryCache.snapshot().keySet()) {
            if (key.startsWith(prefix)) {
                memoryCache.remove(key);
            }
        }
    }

    private int parseIntMetadata(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLongMetadata(@Nullable String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Bitmap applyImageOrientation(Bitmap bitmap, File source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return bitmap;
        }
        try {
            ExifInterface exif = new ExifInterface(source.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return rotate(bitmap, 90);
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return rotate(bitmap, 180);
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return rotate(bitmap, 270);
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    return transform(bitmap, -1f, 1f, 0);
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    return transform(bitmap, 1f, -1f, 0);
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return transform(bitmap, -1f, 1f, 90);
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return transform(bitmap, -1f, 1f, 270);
                default:
                    return bitmap;
            }
        } catch (IOException | RuntimeException ignored) {
            return bitmap;
        }
    }

    private Bitmap rotate(Bitmap source, int degrees) {
        return transform(source, 1f, 1f, degrees);
    }

    private Bitmap transform(Bitmap source, float scaleX, float scaleY, int degrees) {
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        if (degrees != 0) {
            matrix.postRotate(degrees);
        }
        Bitmap transformed = Bitmap.createBitmap(source, 0, 0,
                source.getWidth(), source.getHeight(), matrix, true);
        if (transformed != source) {
            source.recycle();
        }
        return transformed;
    }

    private Bitmap scaleDown(Bitmap source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        int largest = Math.max(width, height);
        if (largest <= maxDimension) {
            return source;
        }
        float scale = maxDimension / (float) largest;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    private boolean writeBitmapAtomically(Bitmap bitmap, File outputFile) throws IOException {
        File temporary = new File(outputFile.getParentFile(), outputFile.getName() + ".tmp");
        Bitmap.CompressFormat format = bitmap.hasAlpha()
                ? Bitmap.CompressFormat.PNG
                : Bitmap.CompressFormat.JPEG;
        int quality = bitmap.hasAlpha() ? 100 : 92;
        boolean success = false;
        try {
            try (BufferedOutputStream output = new BufferedOutputStream(
                    new FileOutputStream(temporary), 128 * 1024)) {
                if (!bitmap.compress(format, quality, output)) {
                    return false;
                }
            }
            if (outputFile.exists() && !outputFile.delete()) {
                return false;
            }
            if (!temporary.renameTo(outputFile)) {
                copyFile(temporary, outputFile);
            }
            //noinspection ResultOfMethodCallIgnored
            outputFile.setLastModified(System.currentTimeMillis());
            success = outputFile.isFile() && outputFile.length() > 0;
            return success;
        } finally {
            deleteQuietly(temporary);
            if (!success) {
                deleteQuietly(outputFile);
            }
        }
    }

    @Nullable
    private Bitmap decodeForDisplay(File file, int requestedSizePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight,
                Math.max(128, requestedSizePx));
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private int calculateInSampleSize(int width, int height, int requestedLargestDimension) {
        int sample = 1;
        int largest = Math.max(width, height);
        while (largest / (sample * 2) >= requestedLargestDimension) {
            sample *= 2;
        }
        return sample;
    }

    private int[] fitInside(int width, int height, int maxDimension) {
        int largest = Math.max(width, height);
        if (largest <= maxDimension) {
            return new int[]{Math.max(1, width), Math.max(1, height)};
        }
        float scale = maxDimension / (float) largest;
        return new int[]{Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale))};
    }

    private boolean isUsableThumbnail(File file) {
        if (!file.isFile() || file.length() <= 0) {
            return false;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    private long getImageSourceSizeLimitBytes() {
        int configuredMb = getThumbnailPreferenceMb(
                R.string.pref_key_thumbnail_image_source_max_mb,
                R.integer.default_thumbnail_image_source_max_mb);
        return megabytesToBytes(Math.max(1, configuredMb));
    }

    private long getDiskCacheMaxBytes(CacheStore store) {
        int configuredMb = getThumbnailPreferenceMb(
                store.maxPreferenceKeyResource, store.defaultMaxResource);
        return megabytesToBytes(Math.max(1, configuredMb));
    }

    private long getDiskCacheTrimTargetBytes(CacheStore store, long maximumBytes) {
        int configuredMb = getThumbnailPreferenceMb(
                store.targetPreferenceKeyResource, store.defaultTargetResource);
        return Math.min(maximumBytes, megabytesToBytes(Math.max(1, configuredMb)));
    }

    private int getThumbnailPreferenceMb(int keyResource, int defaultResource) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String key = context.getString(keyResource);
        int defaultValue = context.getResources().getInteger(defaultResource);
        try {
            return preferences.getInt(key, defaultValue);
        } catch (ClassCastException e) {
            preferences.edit().remove(key).apply();
            return defaultValue;
        }
    }

    private long megabytesToBytes(int megabytes) {
        return Math.min((long) megabytes, Long.MAX_VALUE / BYTES_PER_MB) * BYTES_PER_MB;
    }

    /**
     * Presents an rclone remote as a seekable Android media source.
     * MediaMetadataRetriever decides which byte offsets it needs; each cache miss becomes
     * an rclone cat --offset/--count range read, with a small read-ahead window to
     * avoid starting a process for every tiny extractor probe.
     */
    private static final class RcloneRangeMediaDataSource extends MediaDataSource
            implements FfmpegThumbnailExtractor.RandomAccessSource {
        private final Rclone rclone;
        private final String remotePath;
        private final long sourceSize;
        private final List<CachedRange> cachedRanges;
        private boolean closed;

        private RcloneRangeMediaDataSource(Rclone rclone, String remotePath, long sourceSize) {
            this.rclone = rclone;
            this.remotePath = remotePath;
            this.sourceSize = sourceSize > 0 ? sourceSize : -1L;
            this.cachedRanges = new ArrayList<>(REMOTE_MEDIA_CACHED_RANGE_COUNT);
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int size)
                throws IOException {
            ensureOpen();
            if (position < 0) {
                throw new IOException("Media read position must be >= 0");
            }
            if (offset < 0 || size < 0 || offset > buffer.length - size) {
                throw new IndexOutOfBoundsException();
            }
            if (size == 0) {
                return 0;
            }
            if (sourceSize >= 0 && position >= sourceSize) {
                return -1;
            }

            int totalRead = 0;
            while (totalRead < size) {
                long currentPosition = position + totalRead;
                if (sourceSize >= 0 && currentPosition >= sourceSize) {
                    break;
                }

                CachedRange range = findCachedRange(currentPosition);
                if (range == null) {
                    range = fetchRange(currentPosition, size - totalRead);
                    if (range == null) {
                        break;
                    }
                }

                int rangeOffset = (int) (currentPosition - range.start);
                int copyLength = Math.min(size - totalRead, range.data.length - rangeOffset);
                if (copyLength <= 0) {
                    cachedRanges.remove(range);
                    continue;
                }
                System.arraycopy(range.data, rangeOffset, buffer, offset + totalRead, copyLength);
                totalRead += copyLength;
            }
            return totalRead == 0 ? -1 : totalRead;
        }

        @Override
        public synchronized long getSize() throws IOException {
            ensureOpen();
            return sourceSize;
        }

        @Override
        public synchronized void close() {
            closed = true;
            cachedRanges.clear();
        }

        @Nullable
        private CachedRange findCachedRange(long position) {
            for (int index = 0; index < cachedRanges.size(); index++) {
                CachedRange range = cachedRanges.get(index);
                if (position >= range.start && position < range.end()) {
                    if (index != cachedRanges.size() - 1) {
                        cachedRanges.remove(index);
                        cachedRanges.add(range);
                    }
                    return range;
                }
            }
            return null;
        }

        @Nullable
        private CachedRange fetchRange(long position, int minimumBytes) throws IOException {
            long remaining = sourceSize >= 0 ? sourceSize - position : Long.MAX_VALUE;
            if (remaining <= 0) {
                return null;
            }

            int fetchBytes = Math.max(REMOTE_MEDIA_READ_AHEAD_BYTES,
                    Math.min(minimumBytes, REMOTE_MEDIA_MAX_FETCH_BYTES));
            fetchBytes = (int) Math.min((long) fetchBytes, remaining);
            if (fetchBytes <= 0) {
                return null;
            }

            byte[] data = new byte[fetchBytes];
            int totalRead = 0;
            try (InputStream input = rclone.downloadRangeToPipe(
                    remotePath, position, fetchBytes, REMOTE_MEDIA_RANGE_TIMEOUT_MS)) {
                while (totalRead < fetchBytes) {
                    int read = input.read(data, totalRead, fetchBytes - totalRead);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    totalRead += read;
                }
            }
            if (totalRead <= 0) {
                return null;
            }
            if (totalRead != data.length) {
                data = Arrays.copyOf(data, totalRead);
            }

            CachedRange range = new CachedRange(position, data);
            cachedRanges.add(range);
            while (cachedRanges.size() > REMOTE_MEDIA_CACHED_RANGE_COUNT) {
                cachedRanges.remove(0);
            }
            return range;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("MediaDataSource is closed");
            }
        }
    }

    /**
     * Seekable SAF/local source used only by the FFmpeg fallback. Positional FileChannel reads
     * preserve the same AVIO contract as the rclone range source without copying the full video.
     */
    private static final class SeekableContentMediaDataSource extends MediaDataSource
            implements FfmpegThumbnailExtractor.RandomAccessSource {
        private final AssetFileDescriptor descriptor;
        private final FileInputStream input;
        private final FileChannel channel;
        private final long startOffset;
        private final long sourceSize;
        private boolean closed;

        private SeekableContentMediaDataSource(AssetFileDescriptor descriptor, long fallbackSize)
                throws IOException {
            this.descriptor = descriptor;
            this.startOffset = Math.max(0L, descriptor.getStartOffset());
            this.input = new FileInputStream(descriptor.getFileDescriptor());
            this.channel = input.getChannel();

            long descriptorLength = descriptor.getLength();
            long resolvedSize = descriptorLength >= 0 ? descriptorLength : fallbackSize;
            if (resolvedSize <= 0) {
                try {
                    resolvedSize = Math.max(0L, channel.size() - startOffset);
                } catch (IOException ignored) {
                    resolvedSize = -1L;
                }
            }
            this.sourceSize = resolvedSize > 0 ? resolvedSize : -1L;
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int size)
                throws IOException {
            ensureOpen();
            if (position < 0) {
                throw new IOException("Media read position must be >= 0");
            }
            if (offset < 0 || size < 0 || offset > buffer.length - size) {
                throw new IndexOutOfBoundsException();
            }
            if (size == 0) {
                return 0;
            }
            if (sourceSize >= 0 && position >= sourceSize) {
                return -1;
            }
            if (position > Long.MAX_VALUE - startOffset) {
                throw new IOException("Media read position overflow");
            }

            int requested = size;
            if (sourceSize >= 0) {
                requested = (int) Math.min((long) requested, sourceSize - position);
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, requested);
            int read = channel.read(byteBuffer, startOffset + position);
            return read <= 0 ? -1 : read;
        }

        @Override
        public synchronized long getSize() throws IOException {
            ensureOpen();
            return sourceSize;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                channel.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                input.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
            try {
                descriptor.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("MediaDataSource is closed");
            }
        }
    }

    private static final class CachedRange {
        private final long start;
        private final byte[] data;

        private CachedRange(long start, byte[] data) {
            this.start = start;
            this.data = data;
        }

        private long end() {
            return start + data.length;
        }
    }

    private static final class SourceSizeLimitExceededException extends IOException {
        private SourceSizeLimitExceededException() {
            super("Image source exceeds configured thumbnail download limit");
        }
    }

    private static final class ArchiveEntryTooLargeException extends IOException {
        private ArchiveEntryTooLargeException() {
            super("Thumbnail archive entry exceeds the supported size");
        }
    }

    private void initializeCacheDirectories() {
        ensureDirectory(thumbnailRootDirectory);
        ensureDirectory(workRootDirectory);
        File rootMarker = new File(
                thumbnailRootDirectory, CACHE_ROOT_VERSION_MARKER_NAME);
        String storedRootVersion = readSmallTextFile(rootMarker);
        if (!CACHE_ROOT_VERSION.equals(storedRootVersion)) {
            imageCache.cacheGeneration.incrementAndGet();
            videoCache.cacheGeneration.incrementAndGet();
            imageMemoryCache.evictAll();
            videoMemoryCache.evictAll();
            deleteDirectoryContents(thumbnailRootDirectory);
            deleteDirectoryContents(workRootDirectory);
            ensureDirectory(imageCache.directory);
            ensureDirectory(videoCache.directory);
            ensureDirectory(imageCache.workDirectory);
            ensureDirectory(videoCache.workDirectory);
            imageCache.statisticsMutationGeneration.incrementAndGet();
            videoCache.statisticsMutationGeneration.incrementAndGet();
            invalidatePersistedCacheStatistics(imageCache);
            invalidatePersistedCacheStatistics(videoCache);
        }
        ensureDirectory(imageCache.directory);
        ensureDirectory(videoCache.directory);
        ensureDirectory(imageCache.workDirectory);
        ensureDirectory(videoCache.workDirectory);
        initializeCacheDirectory(imageCache);
        initializeCacheDirectory(videoCache);
        writeVersionMarker(thumbnailRootDirectory, CACHE_ROOT_VERSION_MARKER_NAME,
                CACHE_ROOT_VERSION);
    }

    private void initializeCacheDirectory(CacheStore store) {
        synchronized (store.diskCacheLock) {
            ensureDirectory(store.directory);
            File marker = new File(store.directory, CACHE_VERSION_MARKER_NAME);
            String storedVersion = readSmallTextFile(marker);
            if (!store.cacheVersion.equals(storedVersion)) {
                store.cacheGeneration.incrementAndGet();
                getMemoryCache(store).evictAll();
                deleteDirectoryContents(store.directory);
                ensureDirectory(store.directory);
                store.statisticsMutationGeneration.incrementAndGet();
                invalidatePersistedCacheStatistics(store);
            }
            writeCacheVersionMarkerLocked(store);
        }
    }

    private void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create thumbnail directory: "
                    + directory.getAbsolutePath());
        }
    }

    @Nullable
    private String readSmallTextFile(File file) {
        if (!file.isFile() || file.length() <= 0L || file.length() > 1024L) {
            return null;
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return null;
        }
    }

    private void writeCacheVersionMarkerLocked(CacheStore store) {
        writeVersionMarker(store.directory, CACHE_VERSION_MARKER_NAME, store.cacheVersion);
    }

    private void writeVersionMarker(File directory, String markerName, String version) {
        File marker = new File(directory, markerName);
        File temporary = new File(directory, markerName + ".tmp");
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
            output.write(version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            deleteQuietly(temporary);
            FLog.e(TAG, "Unable to write thumbnail cache version marker", error);
            return;
        }
        if (marker.exists() && !marker.delete()) {
            deleteQuietly(temporary);
            FLog.d(TAG, "Unable to replace thumbnail cache version marker");
            return;
        }
        if (!temporary.renameTo(marker)) {
            try {
                copyFile(temporary, marker);
            } catch (IOException error) {
                FLog.e(TAG, "Unable to finalize thumbnail cache version marker", error);
            } finally {
                deleteQuietly(temporary);
            }
        }
    }

    private boolean awaitCacheInitialization() {
        try {
            cacheInitializationLatch.await();
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Nullable
    private CacheStatistics loadPersistedCacheStatistics(CacheStore store) {
        SharedPreferences preferences = context.getSharedPreferences(
                store.statePreferencesName, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(CACHE_STATE_VALID, false)
                || !store.cacheVersion.equals(
                preferences.getString(CACHE_STATE_VERSION, null))) {
            return null;
        }
        int count = preferences.getInt(CACHE_STATE_COUNT, -1);
        long bytes = preferences.getLong(CACHE_STATE_BYTES, -1L);
        if (count < 0 || bytes < 0L) {
            return null;
        }
        return new CacheStatistics(count, bytes);
    }

    private void persistCacheStatistics(CacheStore store, CacheStatistics statistics) {
        context.getSharedPreferences(store.statePreferencesName, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(CACHE_STATE_VALID, true)
                .putString(CACHE_STATE_VERSION, store.cacheVersion)
                .putInt(CACHE_STATE_COUNT, statistics.thumbnailCount)
                .putLong(CACHE_STATE_BYTES, statistics.totalBytes)
                .apply();
    }

    private void invalidatePersistedCacheStatistics(CacheStore store) {
        store.cachedStatistics = null;
        store.publishedStatisticsGeneration = -1L;
        context.getSharedPreferences(store.statePreferencesName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private void requestCacheStatisticsRefresh(CacheStore store) {
        if (!store.statisticsRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        maintenanceExecutor.execute(() -> {
            boolean refreshCompleted = false;
            try {
                boolean hasPendingCallbacks;
                synchronized (store.statisticsLock) {
                    hasPendingCallbacks = !store.pendingStatisticsCallbacks.isEmpty();
                }
                if (store.publishedStatisticsGeneration
                        == store.statisticsMutationGeneration.get()
                        && !hasPendingCallbacks) {
                    return;
                }
                long generation = store.statisticsMutationGeneration.get();
                CacheStatistics statistics = calculateCacheStatistics(store);
                publishCacheStatistics(store, statistics, generation);
                if (statistics.totalBytes > getDiskCacheMaxBytes(store)) {
                    requestDiskCacheTrim(store, 0L);
                }
                refreshCompleted = true;
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unable to calculate " + store.type.getArchiveName()
                        + " thumbnail cache statistics", error);
            } finally {
                store.statisticsRefreshScheduled.set(false);
                boolean hasPendingCallbacks;
                synchronized (store.statisticsLock) {
                    hasPendingCallbacks = !store.pendingStatisticsCallbacks.isEmpty();
                }
                if (refreshCompleted
                        && (store.publishedStatisticsGeneration
                        != store.statisticsMutationGeneration.get()
                        || hasPendingCallbacks)) {
                    requestCacheStatisticsRefresh(store);
                }
            }
        });
    }

    private CacheStatistics calculateCacheStatistics(CacheStore store) {
        File[] files = store.directory.listFiles(
                (dir, name) -> THUMBNAIL_FILE_PATTERN.matcher(name).matches());
        if (files == null || files.length == 0) {
            return new CacheStatistics(0, 0L);
        }
        int count = 0;
        long total = 0L;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            count++;
            total = saturatedAdd(total, Math.max(0L, file.length()));
        }
        return new CacheStatistics(count, total);
    }

    private void publishCacheStatistics(CacheStore store, CacheStatistics statistics,
                                        long generation) {
        if (generation != store.statisticsMutationGeneration.get()) {
            return;
        }
        store.cachedStatistics = statistics;
        store.publishedStatisticsGeneration = generation;
        persistCacheStatistics(store, statistics);

        List<CacheStatisticsCallback> callbacks;
        synchronized (store.statisticsLock) {
            if (store.pendingStatisticsCallbacks.isEmpty()) {
                return;
            }
            callbacks = new ArrayList<>(store.pendingStatisticsCallbacks);
            store.pendingStatisticsCallbacks.clear();
        }
        for (CacheStatisticsCallback callback : callbacks) {
            mainHandler.post(() -> callback.onCacheStatisticsLoaded(statistics));
        }
    }

    private void requestDiskCacheTrim(CacheStore store, long delayMs) {
        store.trimRequestGeneration.incrementAndGet();
        enqueueDiskCacheTrim(store, delayMs);
    }

    private void enqueueDiskCacheTrim(CacheStore store, long delayMs) {
        if (!store.trimScheduled.compareAndSet(false, true)) {
            return;
        }
        maintenanceExecutor.schedule(() -> {
            long handledGeneration = store.trimRequestGeneration.get();
            try {
                trimDiskCache(store);
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unable to trim " + store.type.getArchiveName()
                        + " thumbnail cache", error);
            } finally {
                store.trimScheduled.set(false);
                if (store.trimRequestGeneration.get() != handledGeneration) {
                    enqueueDiskCacheTrim(store, 0L);
                }
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    @Nullable
    private ZipEntry nextNonDirectoryEntry(ZipInputStream zipInput) throws IOException {
        ZipEntry entry;
        while ((entry = zipInput.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                return entry;
            }
            zipInput.closeEntry();
        }
        return null;
    }

    @Nullable
    private String getArchiveThumbnailFileName(CacheStore store, String entryName) {
        if (entryName == null || !entryName.startsWith(store.archiveThumbnailPrefix)) {
            return null;
        }
        String fileName = entryName.substring(store.archiveThumbnailPrefix.length());
        if (fileName.isEmpty() || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || !THUMBNAIL_FILE_PATTERN.matcher(fileName).matches()) {
            return null;
        }
        return fileName;
    }

    private long copyZipEntryToFile(ZipInputStream zipInput, File destination,
                                    long maximumBytes) throws IOException {
        long total = 0L;
        byte[] buffer = new byte[128 * 1024];
        try (OutputStream output = new BufferedOutputStream(
                new FileOutputStream(destination), 128 * 1024)) {
            int read;
            while ((read = zipInput.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (total > maximumBytes - read) {
                    throw new ArchiveEntryTooLargeException();
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private long discardZipEntry(ZipInputStream zipInput, long maximumBytes)
            throws IOException {
        long total = 0L;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = zipInput.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maximumBytes - read) {
                throw new ArchiveEntryTooLargeException();
            }
            total += read;
        }
        return total;
    }

    private byte[] readZipEntryBytes(ZipInputStream zipInput, long maximumBytes)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = zipInput.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maximumBytes - read) {
                throw new ArchiveEntryTooLargeException();
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private void replaceImportedThumbnailLocked(CacheStore store, File source, File destination)
            throws IOException {
        File staging = new File(store.directory, destination.getName() + ".importing");
        File backup = new File(store.directory, destination.getName() + ".backup");
        boolean destinationOriginallyExisted = destination.exists();
        deleteQuietly(staging);
        deleteQuietly(backup);
        boolean destinationBackedUp = false;
        try {
            copyFile(source, staging);
            if (!isUsableThumbnail(staging)) {
                throw new IOException("Imported thumbnail is not decodable");
            }
            if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    throw new IOException("Unable to back up existing thumbnail");
                }
                destinationBackedUp = true;
            }
            if (!staging.renameTo(destination)) {
                copyFile(staging, destination);
            }
            if (!destination.isFile() || destination.length() <= 0L) {
                throw new IOException("Imported thumbnail was not written");
            }
            deleteQuietly(backup);
            destinationBackedUp = false;
        } catch (IOException error) {
            if (destinationBackedUp || !destinationOriginallyExisted) {
                deleteQuietly(destination);
            }
            if (destinationBackedUp) {
                if (!backup.renameTo(destination)) {
                    try {
                        copyFile(backup, destination);
                        deleteQuietly(backup);
                    } catch (IOException restoreError) {
                        error.addSuppressed(restoreError);
                    }
                }
                destinationBackedUp = false;
            }
            throw error;
        } finally {
            deleteQuietly(staging);
            if (!destinationBackedUp) {
                deleteQuietly(backup);
            }
        }
    }

    private long saturatedAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void deleteDirectoryContents(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            deleteRecursively(file);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            deleteDirectoryContents(file);
        }
        deleteQuietly(file);
    }

    private void cleanupWorkDirectory(CacheStore store) {
        deleteDirectoryContents(store.workDirectory);
        ensureDirectory(store.workDirectory);
    }

    private void cleanupExpiredFailureMarkers(CacheStore store) {
        File[] failures = store.directory.listFiles((dir, name) -> name.endsWith(".failed"));
        if (failures == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File failure : failures) {
            if (now - failure.lastModified() >= FAILURE_CACHE_DURATION_MS) {
                deleteQuietly(failure);
            }
        }
    }

    private boolean isRecentFailure(File failure) {
        return failure.isFile()
                && System.currentTimeMillis() - failure.lastModified() < FAILURE_CACHE_DURATION_MS;
    }

    private void markFailure(File failure) {
        try (FileOutputStream ignored = new FileOutputStream(failure)) {
            // The timestamp is the negative-cache payload.
        } catch (IOException ignored) {
            // A failed negative-cache write only causes a later retry.
        }
    }

    private boolean deleteCachedThumbnail(CacheStore store, File thumbnail) {
        synchronized (store.diskCacheLock) {
            if (!thumbnail.isFile()) {
                return false;
            }
            if (!thumbnail.delete()) {
                FLog.d(TAG, "Unable to remove cached thumbnail: %s",
                        thumbnail.getAbsolutePath());
                return false;
            }
            store.statisticsMutationGeneration.incrementAndGet();
            return true;
        }
    }

    private void trimDiskCache(CacheStore store) {
        CacheStatistics statistics;
        long statisticsGeneration;
        synchronized (store.diskCacheLock) {
            File[] files = store.directory.listFiles(
                    (dir, name) -> THUMBNAIL_FILE_PATTERN.matcher(name).matches());
            long maximumBytes = getDiskCacheMaxBytes(store);
            long trimTargetBytes = getDiskCacheTrimTargetBytes(store, maximumBytes);
            int count = 0;
            long total = 0L;
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile()) {
                        continue;
                    }
                    count++;
                    total = saturatedAdd(total, Math.max(0L, file.length()));
                }

                if (total > maximumBytes) {
                    Arrays.sort(files,
                            (left, right) -> Long.compare(
                                    left.lastModified(), right.lastModified()));
                    boolean removedAny = false;
                    for (File file : files) {
                        if (!file.isFile()) {
                            continue;
                        }
                        long length = Math.max(0L, file.length());
                        if (file.delete()) {
                            removedAny = true;
                            count = Math.max(0, count - 1);
                            total = Math.max(0L, total - length);
                        }
                        if (total <= trimTargetBytes) {
                            break;
                        }
                    }
                    if (removedAny) {
                        store.statisticsMutationGeneration.incrementAndGet();
                    }
                }
            }
            statisticsGeneration = store.statisticsMutationGeneration.get();
            statistics = new CacheStatistics(count, total);
        }
        publishCacheStatistics(store, statistics, statisticsGeneration);
    }

    private String getSafeSuffix(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return ".media";
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]", "");
        if (extension.isEmpty() || extension.length() > 10) {
            return ".media";
        }
        return '.' + extension;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(result.length * 2);
            for (byte b : result) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void deleteQuietly(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (!file.delete()) {
            FLog.d(TAG, "Unable to remove temporary file: %s", file.getAbsolutePath());
        }
    }

    private static final class LoadRequest implements Cancellable {
        private final int requestedSizePx;
        private final String memoryKey;
        private final Callback callback;
        private volatile boolean cancelled;

        private LoadRequest(int requestedSizePx, String memoryKey, Callback callback) {
            this.requestedSizePx = requestedSizePx;
            this.memoryKey = memoryKey;
            this.callback = callback;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private boolean isCancelled() {
            return cancelled;
        }
    }
}
