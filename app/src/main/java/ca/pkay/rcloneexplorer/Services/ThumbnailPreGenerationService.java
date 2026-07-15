package ca.pkay.rcloneexplorer.Services;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.thumbnails.ThumbnailRepository;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.NotificationUtils;
import ca.pkay.rcloneexplorer.util.SyncLog;

/**
 * Traverses a remote folder tree and fills one thumbnail cache type at a time.
 * IntentService serializes submitted jobs, so image and video scans cannot create an
 * unbounded number of concurrent remote reads.
 */
public final class ThumbnailPreGenerationService extends IntentService {

    private static final String TAG = "ThumbnailPreGen";
    private static final String CHANNEL_ID =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation_channel";
    private static final String CHANNEL_NAME = "Thumbnail generation";
    private static final int NOTIFICATION_ID = 181;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 750L;
    private static final long SIDEBAR_LOG_UPDATE_INTERVAL_MS = 5_000L;
    private static final String THUMBNAIL_LOG_TITLE = "Thumbnail diagnostics";

    public static final String REMOTE_ARG =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.remote";
    public static final String DIRECTORY_PATH_ARG =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.path";
    public static final String START_AT_ROOT_ARG =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.start_at_root";
    public static final String CACHE_TYPE_ARG =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.cache_type";

    public static final String ACTION_COMPLETED =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.completed";
    public static final String RESULT_REMOTE_NAME =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.result.remote";
    public static final String RESULT_ROOT_PATH =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.result.path";
    public static final String RESULT_CACHE_TYPE =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.result.cache_type";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public ThumbnailPreGenerationService() {
        super("ca.pkay.rcloneexplorer.thumbnailpregeneration");
        setIntentRedelivery(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationUtils.createNotificationChannel(
                this,
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
                getString(R.string.thumbnail_recursive_notification_channel_description));
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        RemoteItem remote = intent.getParcelableExtra(REMOTE_ARG);
        String rootPath = intent.getStringExtra(DIRECTORY_PATH_ARG);
        boolean startAtRoot = intent.getBooleanExtra(START_AT_ROOT_ARG, false);
        ThumbnailRepository.CacheType cacheType = parseCacheType(
                intent.getStringExtra(CACHE_TYPE_ARG));
        if (remote == null || rootPath == null || cacheType == null) {
            FLog.e(TAG, "Ignoring malformed recursive thumbnail generation request");
            return;
        }

        SyncLog.info(this, THUMBNAIL_LOG_TITLE,
                "RECURSIVE START | media=" + cacheType.name().toLowerCase(Locale.US)
                        + " | remote=" + sanitizeLogValue(remote.getName())
                        + " | root=" + sanitizeLogValue(rootPath));
        startForeground(NOTIFICATION_ID, buildActiveNotification(cacheType, 0, 0));
        acquireResourceLocks();
        GenerationSummary summary;
        try {
            summary = generateRecursively(remote, rootPath, startAtRoot, cacheType);
        } catch (RuntimeException error) {
            FLog.e(TAG, "Recursive thumbnail generation failed", error);
            SyncLog.error(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE ERROR | media=" + cacheType.name().toLowerCase(Locale.US)
                            + " | remote=" + sanitizeLogValue(remote.getName())
                            + " | root=" + sanitizeLogValue(rootPath)
                            + " | error=" + error.getClass().getSimpleName()
                            + ": " + sanitizeLogValue(error.getMessage()));
            summary = new GenerationSummary();
            summary.fatalError = true;
        } finally {
            releaseResourceLocks();
        }

        SyncLog.info(this, THUMBNAIL_LOG_TITLE,
                "RECURSIVE FINISH | media=" + cacheType.name().toLowerCase(Locale.US)
                        + " | remote=" + sanitizeLogValue(remote.getName())
                        + " | root=" + sanitizeLogValue(rootPath)
                        + " | " + summaryForLog(summary));
        NotificationUtils.createNotification(
                this,
                NOTIFICATION_ID,
                buildCompletedNotification(cacheType, summary));
        stopForeground(false);
        broadcastCompletion(remote, rootPath, cacheType);
    }

    private GenerationSummary generateRecursively(RemoteItem remote, String rootPath,
                                                   boolean startAtRoot,
                                                   ThumbnailRepository.CacheType cacheType) {
        ThumbnailRepository repository = ThumbnailRepository.getInstance(this);
        GenerationSummary summary = new GenerationSummary();
        if (!isEnabled(repository, cacheType)) {
            summary.disabled = true;
            return summary;
        }

        Rclone rclone = new Rclone(this);
        Queue<String> pendingFolders = new ArrayDeque<>();
        Set<String> visitedFolders = new HashSet<>();
        pendingFolders.add(rootPath);
        long lastNotificationUpdate = 0L;
        long lastSidebarLogUpdate = 0L;

        while (!pendingFolders.isEmpty() && !Thread.currentThread().isInterrupted()) {
            if (!isEnabled(repository, cacheType)) {
                summary.disabled = true;
                break;
            }

            String folderPath = pendingFolders.remove();
            if (!visitedFolders.add(folderPath)) {
                continue;
            }

            SyncLog.info(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE SCAN | media=" + cacheType.name().toLowerCase(Locale.US)
                            + " | remote=" + sanitizeLogValue(remote.getName())
                            + " | folder=" + sanitizeLogValue(folderPath)
                            + " | pendingFolders=" + pendingFolders.size()
                            + " | " + summaryForLog(summary));
            List<FileItem> content = rclone.getDirectoryContent(
                    remote, folderPath, startAtRoot);
            if (content == null) {
                summary.failedFolders++;
                if (summary.scannedFolders == 0 && folderPath.equals(rootPath)) {
                    summary.fatalError = true;
                    break;
                }
                continue;
            }
            summary.scannedFolders++;

            for (FileItem item : content) {
                if (Thread.currentThread().isInterrupted()) {
                    summary.cancelled = true;
                    break;
                }
                if (item.isDir()) {
                    String childPath = item.getPath();
                    if (childPath != null && !visitedFolders.contains(childPath)) {
                        pendingFolders.add(childPath);
                    }
                    continue;
                }
                if (!matchesType(repository, item, cacheType)) {
                    continue;
                }

                ThumbnailRepository.PreGenerationResult result =
                        repository.preGenerateThumbnail(item, cacheType);
                switch (result) {
                    case GENERATED:
                        summary.generated++;
                        break;
                    case ALREADY_CACHED:
                        summary.alreadyCached++;
                        break;
                    case DISABLED:
                        summary.disabled = true;
                        break;
                    case RECENT_FAILURE:
                        summary.recentFailures++;
                        break;
                    case FAILED:
                        summary.failedFiles++;
                        break;
                    case UNSUPPORTED:
                    default:
                        summary.skipped++;
                        break;
                }

                if (summary.disabled) {
                    break;
                }
                long now = System.currentTimeMillis();
                if (now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                    updateActiveNotification(cacheType, summary);
                    lastNotificationUpdate = now;
                }
                if (now - lastSidebarLogUpdate >= SIDEBAR_LOG_UPDATE_INTERVAL_MS) {
                    SyncLog.info(this, THUMBNAIL_LOG_TITLE,
                            "RECURSIVE PROGRESS | media="
                                    + cacheType.name().toLowerCase(Locale.US)
                                    + " | remote=" + sanitizeLogValue(remote.getName())
                                    + " | current=" + sanitizeLogValue(item.getPath())
                                    + " | pendingFolders=" + pendingFolders.size()
                                    + " | " + summaryForLog(summary));
                    lastSidebarLogUpdate = now;
                }
            }

            if (summary.disabled || summary.cancelled) {
                break;
            }
            updateActiveNotification(cacheType, summary);
        }
        return summary;
    }

    private static String summaryForLog(GenerationSummary summary) {
        return "folders=" + summary.scannedFolders
                + ", generated=" + summary.generated
                + ", cached=" + summary.alreadyCached
                + ", recentFailures=" + summary.recentFailures
                + ", failedFiles=" + summary.failedFiles
                + ", failedFolders=" + summary.failedFolders
                + ", skipped=" + summary.skipped
                + ", disabled=" + summary.disabled
                + ", cancelled=" + summary.cancelled
                + ", fatalError=" + summary.fatalError;
    }

    private static String sanitizeLogValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private void updateActiveNotification(ThumbnailRepository.CacheType cacheType,
                                          GenerationSummary summary) {
        NotificationUtils.createNotification(
                this,
                NOTIFICATION_ID,
                buildActiveNotification(
                        cacheType,
                        summary.scannedFolders,
                        summary.processedFiles()));
    }

    private android.app.Notification buildActiveNotification(
            ThumbnailRepository.CacheType cacheType, int folders, int files) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streaming)
                .setContentTitle(getString(getTaskTitle(cacheType)))
                .setContentText(getString(
                        R.string.thumbnail_recursive_notification_progress,
                        folders,
                        files))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build();
    }

    private android.app.Notification buildCompletedNotification(
            ThumbnailRepository.CacheType cacheType, GenerationSummary summary) {
        int textResource;
        String text;
        if (summary.disabled) {
            textResource = cacheType == ThumbnailRepository.CacheType.IMAGE
                    ? R.string.thumbnail_recursive_image_disabled
                    : R.string.thumbnail_recursive_video_disabled;
            text = getString(textResource);
        } else if (summary.fatalError) {
            text = getString(R.string.thumbnail_recursive_notification_failed);
        } else if (summary.processedFiles() == 0 && summary.failedFolders == 0) {
            text = getString(R.string.thumbnail_recursive_notification_no_files);
        } else {
            text = getString(
                    R.string.thumbnail_recursive_notification_completed,
                    summary.generated,
                    summary.alreadyCached,
                    summary.failedFiles + summary.recentFailures,
                    summary.failedFolders);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streaming)
                .setContentTitle(getString(getTaskTitle(cacheType)))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
                .build();
    }

    private int getTaskTitle(ThumbnailRepository.CacheType cacheType) {
        return cacheType == ThumbnailRepository.CacheType.IMAGE
                ? R.string.thumbnail_recursive_notification_title_image
                : R.string.thumbnail_recursive_notification_title_video;
    }

    private boolean matchesType(ThumbnailRepository repository, FileItem item,
                                ThumbnailRepository.CacheType cacheType) {
        return cacheType == ThumbnailRepository.CacheType.IMAGE
                ? repository.isImageThumbnailCandidate(item)
                : repository.isVideoThumbnailCandidate(item);
    }

    private boolean isEnabled(ThumbnailRepository repository,
                              ThumbnailRepository.CacheType cacheType) {
        return cacheType == ThumbnailRepository.CacheType.IMAGE
                ? repository.areImageThumbnailsEnabled()
                : repository.areVideoThumbnailsEnabled();
    }

    @Nullable
    private ThumbnailRepository.CacheType parseCacheType(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return ThumbnailRepository.CacheType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void broadcastCompletion(RemoteItem remote, String rootPath,
                                     ThumbnailRepository.CacheType cacheType) {
        Intent result = new Intent(ACTION_COMPLETED);
        result.putExtra(RESULT_REMOTE_NAME, remote.getName());
        result.putExtra(RESULT_ROOT_PATH, rootPath);
        result.putExtra(RESULT_CACHE_TYPE, cacheType.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(result);
    }

    @SuppressLint("WakelockTimeout")
    private void acquireResourceLocks() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":thumbnail-pre-generation");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                getPackageName() + ":thumbnail-pre-generation-wifi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
    }

    private void releaseResourceLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException error) {
            FLog.e(TAG, "Unable to release thumbnail wake lock", error);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (RuntimeException error) {
            FLog.e(TAG, "Unable to release thumbnail Wi-Fi lock", error);
        }
        wakeLock = null;
        wifiLock = null;
    }

    private static final class GenerationSummary {
        private int scannedFolders;
        private int failedFolders;
        private int generated;
        private int alreadyCached;
        private int recentFailures;
        private int failedFiles;
        private int skipped;
        private boolean disabled;
        private boolean fatalError;
        private boolean cancelled;

        private int processedFiles() {
            return generated + alreadyCached + recentFailures + failedFiles + skipped;
        }
    }
}
