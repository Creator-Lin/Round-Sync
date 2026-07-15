package ca.pkay.rcloneexplorer.Services;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
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
import ca.pkay.rcloneexplorer.util.PermissionManager;
import ca.pkay.rcloneexplorer.util.SyncLog;

/**
 * Traverses a remote folder tree and fills one thumbnail cache type at a time.
 * IntentService serializes submitted jobs, so image and video scans cannot create an
 * unbounded number of concurrent remote reads.
 */
public final class ThumbnailPreGenerationService extends IntentService {

    private static final String TAG = "ThumbnailPreGen";
    public static final String CHANNEL_ID =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation_channel";
    private static final String CHANNEL_NAME = "Thumbnail generation";
    private static final int NOTIFICATION_ID = 181;
    private static final int IMAGE_COMPLETION_NOTIFICATION_ID = 182;
    private static final int VIDEO_COMPLETION_NOTIFICATION_ID = 183;
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
    private static final String SUBMITTED_AT_ARG =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.submitted_at";

    public static final String ACTION_COMPLETED =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.completed";
    public static final String RESULT_REMOTE_NAME =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.result.remote";
    public static final String RESULT_ROOT_PATH =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.result.path";
    public static final String RESULT_CACHE_TYPE =
            "ca.pkay.rcloneexplorer.thumbnail_pre_generation.result.cache_type";

    public enum EnqueueResult {
        STARTED,
        STARTED_WITHOUT_VISIBLE_NOTIFICATIONS,
        FAILED
    }

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public ThumbnailPreGenerationService() {
        super("ca.pkay.rcloneexplorer.thumbnailpregeneration");
        setIntentRedelivery(true);
    }

    public static EnqueueResult enqueue(Context context, RemoteItem remote, String rootPath,
                                        boolean startAtRoot,
                                        ThumbnailRepository.CacheType cacheType) {
        Context appContext = context.getApplicationContext();
        ensureNotificationChannel(appContext);
        boolean notificationsVisible = canDisplayNotifications(appContext);

        Intent intent = new Intent(appContext, ThumbnailPreGenerationService.class);
        intent.putExtra(REMOTE_ARG, remote);
        intent.putExtra(DIRECTORY_PATH_ARG, rootPath);
        intent.putExtra(START_AT_ROOT_ARG, startAtRoot);
        intent.putExtra(CACHE_TYPE_ARG, cacheType.name());
        intent.putExtra(SUBMITTED_AT_ARG, System.currentTimeMillis());

        try {
            ComponentName component = ContextCompat.startForegroundService(appContext, intent);
            if (component == null) {
                SyncLog.thumbnailError(appContext, THUMBNAIL_LOG_TITLE,
                        "RECURSIVE SUBMIT FAILED | media="
                                + cacheType.name().toLowerCase(Locale.US)
                                + " | remote=" + sanitizeLogValue(remote.getName())
                                + " | root=" + sanitizeLogValue(rootPath)
                                + " | error=startForegroundService returned null");
                return EnqueueResult.FAILED;
            }
            // Do not write the process-wide sidebar log here. This method runs on the UI
            // thread, and a contended log file lock could delay onStartCommand() enough to
            // violate the foreground-service promotion deadline. The service records
            // FOREGROUND READY as soon as promotion succeeds.
            return notificationsVisible
                    ? EnqueueResult.STARTED
                    : EnqueueResult.STARTED_WITHOUT_VISIBLE_NOTIFICATIONS;
        } catch (RuntimeException error) {
            FLog.e(TAG, "Unable to start recursive thumbnail foreground service", error);
            SyncLog.thumbnailError(appContext, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE SUBMIT FAILED | media="
                            + cacheType.name().toLowerCase(Locale.US)
                            + " | remote=" + sanitizeLogValue(remote.getName())
                            + " | root=" + sanitizeLogValue(rootPath)
                            + " | error=" + error.getClass().getSimpleName()
                            + ": " + sanitizeLogValue(error.getMessage()));
            return EnqueueResult.FAILED;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ThumbnailRepository.CacheType cacheType = intent == null
                ? null
                : parseCacheType(intent.getStringExtra(CACHE_TYPE_ARG));
        if (cacheType == null) {
            FLog.e(TAG, "Ignoring malformed recursive thumbnail generation request");
            SyncLog.thumbnailError(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE SERVICE REJECTED | startId=" + startId
                            + " | error=missing or invalid cache type");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // startForegroundService() must be followed by startForeground promptly. Do this on
        // the main service callback, before IntentService queues the request or any disk log.
        try {
            promoteToForeground(cacheType, 0, 0);
        } catch (RuntimeException error) {
            FLog.e(TAG, "Unable to promote recursive thumbnail service to foreground", error);
            SyncLog.thumbnailError(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE FOREGROUND FAILED | startId=" + startId
                            + " | media=" + cacheType.name().toLowerCase(Locale.US)
                            + " | error=" + error.getClass().getSimpleName()
                            + ": " + sanitizeLogValue(error.getMessage()));
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        SyncLog.thumbnailInfo(this, THUMBNAIL_LOG_TITLE,
                "RECURSIVE FOREGROUND READY | startId=" + startId
                        + " | media=" + cacheType.name().toLowerCase(Locale.US)
                        + " | " + notificationStateForLog(this));
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        RemoteItem remote = intent.getParcelableExtra(REMOTE_ARG);
        String rootPath = intent.getStringExtra(DIRECTORY_PATH_ARG);
        boolean startAtRoot = intent.getBooleanExtra(START_AT_ROOT_ARG, false);
        long submittedAt = intent.getLongExtra(SUBMITTED_AT_ARG, 0L);
        long queueWaitMs = submittedAt <= 0L
                ? -1L
                : Math.max(0L, System.currentTimeMillis() - submittedAt);
        ThumbnailRepository.CacheType cacheType = parseCacheType(
                intent.getStringExtra(CACHE_TYPE_ARG));
        if (remote == null || rootPath == null || cacheType == null) {
            FLog.e(TAG, "Ignoring malformed recursive thumbnail generation request");
            SyncLog.thumbnailError(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE WORK REJECTED | error=missing remote, root path or cache type");
            stopForeground(true);
            return;
        }

        GenerationSummary summary = new GenerationSummary();
        try {
            // Re-assert the currently handled media type because another queued start command
            // may have refreshed the shared foreground notification in the meantime.
            promoteToForeground(cacheType, 0, 0);
            SyncLog.thumbnailInfo(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE START | media=" + cacheType.name().toLowerCase(Locale.US)
                            + " | remote=" + sanitizeLogValue(remote.getName())
                            + " | root=" + sanitizeLogValue(rootPath)
                            + " | queueWaitMs=" + queueWaitMs);
            acquireResourceLocks();
            summary = generateRecursively(remote, rootPath, startAtRoot, cacheType);
        } catch (RuntimeException error) {
            FLog.e(TAG, "Recursive thumbnail generation failed", error);
            SyncLog.thumbnailError(this, THUMBNAIL_LOG_TITLE,
                    "RECURSIVE ERROR | media=" + cacheType.name().toLowerCase(Locale.US)
                            + " | remote=" + sanitizeLogValue(remote.getName())
                            + " | root=" + sanitizeLogValue(rootPath)
                            + " | error=" + error.getClass().getSimpleName()
                            + ": " + sanitizeLogValue(error.getMessage()));
            summary.fatalError = true;
        } finally {
            releaseResourceLocks();
        }

        SyncLog.thumbnailInfo(this, THUMBNAIL_LOG_TITLE,
                "RECURSIVE FINISH | media=" + cacheType.name().toLowerCase(Locale.US)
                        + " | remote=" + sanitizeLogValue(remote.getName())
                        + " | root=" + sanitizeLogValue(rootPath)
                        + " | " + summaryForLog(summary));

        // Remove the ongoing foreground entry first, then publish the result under a
        // different ID. Keeping foreground lifecycle and completion notifications separate
        // prevents stopForeground() or a newly queued task from erasing the final summary.
        stopForeground(true);
        NotificationUtils.createNotification(
                this,
                getCompletionNotificationId(cacheType),
                buildCompletedNotification(cacheType, summary));
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

            SyncLog.thumbnailInfo(this, THUMBNAIL_LOG_TITLE,
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
            // Publish folder progress before processing the first matching file. If that file
            // blocks during remote download or decoding, the notification still proves that
            // directory traversal reached this point.
            updateActiveNotification(cacheType, summary);

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
                    SyncLog.thumbnailInfo(this, THUMBNAIL_LOG_TITLE,
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

    private static void ensureNotificationChannel(Context context) {
        NotificationUtils.createNotificationChannel(
                context,
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
                context.getString(R.string.thumbnail_recursive_notification_channel_description));
    }

    private static boolean canDisplayNotifications(Context context) {
        if (!new PermissionManager(context).grantedNotifications()
                || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = manager == null
                    ? null
                    : manager.getNotificationChannel(CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    private static String notificationStateForLog(Context context) {
        boolean permissionGranted = new PermissionManager(context).grantedNotifications();
        boolean appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled();
        String channelImportance = "not-applicable";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = manager == null
                    ? null
                    : manager.getNotificationChannel(CHANNEL_ID);
            channelImportance = channel == null
                    ? "missing"
                    : String.valueOf(channel.getImportance());
        }
        return "notificationsVisible=" + canDisplayNotifications(context)
                + " | permissionGranted=" + permissionGranted
                + " | appNotificationsEnabled=" + appEnabled
                + " | channelImportance=" + channelImportance;
    }

    private void promoteToForeground(ThumbnailRepository.CacheType cacheType,
                                     int folders, int files) {
        android.app.Notification notification =
                buildActiveNotification(cacheType, folders, files);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
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
        promoteToForeground(
                cacheType,
                summary.scannedFolders,
                summary.processedFiles());
    }

    private android.app.Notification buildActiveNotification(
            ThumbnailRepository.CacheType cacheType, int folders, int files) {
        String progressText = getString(
                R.string.thumbnail_recursive_notification_progress,
                folders,
                files);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streaming)
                .setContentTitle(getString(getTaskTitle(cacheType)))
                .setContentText(progressText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(progressText))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
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

    private int getCompletionNotificationId(ThumbnailRepository.CacheType cacheType) {
        return cacheType == ThumbnailRepository.CacheType.IMAGE
                ? IMAGE_COMPLETION_NOTIFICATION_ID
                : VIDEO_COMPLETION_NOTIFICATION_ID;
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
