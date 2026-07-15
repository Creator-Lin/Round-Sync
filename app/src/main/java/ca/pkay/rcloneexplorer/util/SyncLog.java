package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ca.pkay.rcloneexplorer.R;

/**
 * Copyright (C) 2021 Felix Nüsse
 *
 * Process-safe, date-partitioned application log shared by rclone and
 * QuarkDav services. This class replaces the original single sync.log file
 * while retaining its public API and legacy-file compatibility.
 */
public class SyncLog {
    public static final String ACTION_CHANGED = "ca.pkay.rcloneexplorer.LOG_CHANGED";
    public static final String ENTRY_ID = "id";
    public static final String TIMESTAMP = "timestamp";
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    private static final String FILE_PREFIX = "sync-";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final int RETENTION_DAYS = 31;
    private static final int MAX_READ_ENTRIES = 5000;
    private static final Object PROCESS_LOCK = new Object();
    private static final ThreadPoolExecutor RCLONE_LOG_WRITER = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            runnable -> {
                Thread thread = new Thread(runnable, "rclone-sidebar-log");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    // QuarkDAV runs in a dedicated Android process. The override is refreshed through a
    // package-scoped broadcast whenever the user changes the setting, so a running service
    // does not keep a stale SharedPreferences value until its process is restarted.
    private static volatile Boolean quarkDavLoggingOverride;

    private interface LockedOperation<T> { T run() throws Exception; }

    private static File logDir(Context c) { return new File(c.getFilesDir(), "logs"); }
    private static File lockFile(Context c) { return new File(logDir(c), ".lock"); }
    private static File legacyFile(Context c) { return new File(c.getFilesDir(), "sync.log"); }
    private static File dailyFile(Context c, long timestamp) {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timestamp));
        return new File(logDir(c), FILE_PREFIX + day + FILE_SUFFIX);
    }

    private static <T> T locked(Context context, LockedOperation<T> operation, T fallback) {
        Context app = context.getApplicationContext();
        File dir = logDir(app);
        if (!dir.exists() && !dir.mkdirs()) return fallback;
        synchronized (PROCESS_LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(lockFile(app), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock ignored = channel.lock()) {
                return operation.run();
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    public static ArrayList<JSONObject> getLog(Context context) {
        return locked(context, () -> {
            ArrayList<JSONObject> entries = new ArrayList<>();
            File[] daily = logDir(context).listFiles((dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
            if (daily != null) {
                Arrays.sort(daily, Comparator.comparing(File::getName).reversed());
                for (File file : daily) {
                    readNewestJsonLines(file, entries, MAX_READ_ENTRIES - entries.size());
                    if (entries.size() >= MAX_READ_ENTRIES) break;
                }
            }
            if (entries.size() < MAX_READ_ENTRIES) {
                readNewestJsonLines(legacyFile(context), entries, MAX_READ_ENTRIES - entries.size());
            }
            entries.sort((a, b) -> Long.compare(b.optLong(TIMESTAMP), a.optLong(TIMESTAMP)));
            if (entries.size() > MAX_READ_ENTRIES) {
                return new ArrayList<>(entries.subList(0, MAX_READ_ENTRIES));
            }
            return entries;
        }, new ArrayList<>());
    }

    /** Reads the tail of a JSONL file so a busy current day never hides its newest entries. */
    private static void readNewestJsonLines(File file, ArrayList<JSONObject> output, int limit) {
        if (!file.exists() || limit <= 0) return;
        ArrayDeque<JSONObject> tail = new ArrayDeque<>(limit);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject entry = new JSONObject(line);
                    if (tail.size() == limit) tail.removeFirst();
                    tail.addLast(entry);
                } catch (JSONException ignored) { }
            }
        } catch (IOException ignored) { }
        Iterator<JSONObject> newestFirst = tail.descendingIterator();
        while (newestFirst.hasNext()) output.add(newestFirst.next());
    }

    private static void appendLog(Context context, String entry, long timestamp) {
        locked(context, () -> {
            File file = dailyFile(context, timestamp);
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(entry.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
                out.getFD().sync();
            }
            prune(context, timestamp);
            return null;
        }, null);
        notifyChanged(context);
    }

    private static void prune(Context context, long now) {
        File[] files = logDir(context).listFiles((dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
        if (files == null) return;
        long cutoff = now - RETENTION_DAYS * 24L * 60L * 60L * 1000L;
        for (File file : files) if (file.lastModified() < cutoff) file.delete();
    }

    public static long log(Context context, String title, String content, int type) {
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(ENTRY_ID, UUID.randomUUID().toString());
            json.put(TIMESTAMP, now);
            json.put(CONTENT, content == null ? "" : content);
            json.put(TITLE, title == null ? "" : title);
            json.put(TYPE, type);
        } catch (JSONException ignored) { }
        appendLog(context, json.toString(), now);
        return now;
    }

    public static long error(Context context, String title, String content) {
        return log(context, title, content, TYPE_ERROR);
    }

    public static long info(Context context, String title, String content) {
        return log(context, title, content, TYPE_INFO);
    }

    public static boolean readQuarkDavLoggingPreference(Context context) {
        Context app = context.getApplicationContext();
        return PreferenceManager.getDefaultSharedPreferences(app).getBoolean(
                app.getString(R.string.pref_key_quarkdav_logs), true);
    }

    public static boolean isQuarkDavLoggingEnabled(Context context) {
        Boolean override = quarkDavLoggingOverride;
        return override != null ? override : readQuarkDavLoggingPreference(context);
    }

    public static void setQuarkDavLoggingEnabledForCurrentProcess(boolean enabled) {
        quarkDavLoggingOverride = enabled;
    }

    public static void clearQuarkDavLoggingOverrideForCurrentProcess() {
        quarkDavLoggingOverride = null;
    }

    public static boolean isRcloneLoggingEnabled(Context context) {
        Context app = context.getApplicationContext();
        return PreferenceManager.getDefaultSharedPreferences(app).getBoolean(
                app.getString(R.string.pref_key_logs), false);
    }

    public static boolean isThumbnailLoggingEnabled(Context context) {
        Context app = context.getApplicationContext();
        return PreferenceManager.getDefaultSharedPreferences(app).getBoolean(
                app.getString(R.string.pref_key_thumbnail_logs), true);
    }

    public static long rcloneError(Context context, String operation, String content) {
        if (!isRcloneLoggingEnabled(context)) {
            return -1L;
        }
        Context app = context.getApplicationContext();
        StringBuilder details = new StringBuilder();
        if (operation != null && !operation.trim().isEmpty()) {
            details.append(app.getString(R.string.rclone_log_operation, operation.trim()))
                    .append('\n');
        }
        details.append(content == null ? "" : content);
        return error(app, app.getString(R.string.rclone_log_title), details.toString().trim());
    }

    /**
     * Queues a sidebar rclone error without slowing the thread that is draining subprocess
     * stderr. The queue is bounded so a pathological error storm cannot grow memory without
     * limit; when full, the oldest pending entry is discarded in favour of the newest one.
     */
    public static void rcloneErrorAsync(Context context, String operation, String content) {
        Context app = context.getApplicationContext();
        if (!isRcloneLoggingEnabled(app)) {
            return;
        }
        RCLONE_LOG_WRITER.execute(() -> rcloneError(app, operation, content));
    }

    public static long quarkDavError(Context context, String title, String content) {
        return isQuarkDavLoggingEnabled(context)
                ? error(context, title, content)
                : -1L;
    }

    public static long quarkDavInfo(Context context, String title, String content) {
        return isQuarkDavLoggingEnabled(context)
                ? info(context, title, content)
                : -1L;
    }

    public static long thumbnailError(Context context, String title, String content) {
        return isThumbnailLoggingEnabled(context)
                ? error(context, title, content)
                : -1L;
    }

    public static long thumbnailInfo(Context context, String title, String content) {
        return isThumbnailLoggingEnabled(context)
                ? info(context, title, content)
                : -1L;
    }

    public static void delete(Context context) {
        locked(context, () -> {
            File[] files = logDir(context).listFiles((dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
            if (files != null) for (File file : files) file.delete();
            legacyFile(context).delete();
            return null;
        }, null);
        notifyChanged(context);
    }

    public static void deleteEntry(Context context, String entryId, long timestamp) {
        locked(context, () -> {
            File[] files = logDir(context).listFiles(
                    (dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
            if (files != null) {
                for (File file : files) rewriteWithoutEntry(file, entryId, timestamp);
            }
            rewriteWithoutEntry(legacyFile(context), entryId, timestamp);
            return null;
        }, null);
        notifyChanged(context);
    }

    private static void rewriteWithoutEntry(File source, String entryId, long timestamp) throws IOException {
        if (!source.exists()) return;
        File temp = new File(source.getParentFile(), source.getName() + ".tmp");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8));
             FileOutputStream out = new FileOutputStream(temp, false)) {
            String line;
            while ((line = reader.readLine()) != null) {
                boolean remove = false;
                try {
                    JSONObject entry = new JSONObject(line);
                    String candidateId = entry.optString(ENTRY_ID, "");
                    remove = entryId != null && !entryId.isEmpty()
                            ? entryId.equals(candidateId)
                            : entry.optLong(TIMESTAMP, -1L) == timestamp;
                } catch (JSONException ignored) { }
                if (!remove) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
            }
            out.flush();
            out.getFD().sync();
        }
        if (temp.length() == 0L) {
            source.delete();
            temp.delete();
        } else if (!temp.renameTo(source)) {
            try (FileInputStream in = new FileInputStream(temp); FileOutputStream out = new FileOutputStream(source, false)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                out.flush();
                out.getFD().sync();
            }
            temp.delete();
        }
    }

    private static void notifyChanged(Context context) {
        context.getApplicationContext().sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
    }
}
