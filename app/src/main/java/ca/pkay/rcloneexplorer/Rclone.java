package ca.pkay.rcloneexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRepository;
import ca.pkay.rcloneexplorer.rclone.Provider;
import ca.pkay.rcloneexplorer.rclone.RemoteReferenceParser;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SyncLog;
import es.dmoral.toasty.Toasty;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.SafDAVServer;
import io.github.x0b.safdav.file.SafConstants;

public class Rclone {

    private static final String TAG = "Rclone";
    private static final AtomicLong RCLONE_CAT_STREAM_SEQUENCE = new AtomicLong();
    private static final AtomicLong RCLONE_STDERR_SEQUENCE = new AtomicLong();
    private static final int MAX_RCLONE_STDERR_CAPTURE_CHARS = 128 * 1024;
    public static final int SYNC_DIRECTION_LOCAL_TO_REMOTE = 1;
    public static final int SYNC_DIRECTION_REMOTE_TO_LOCAL = 2;
    public static final int SERVE_PROTOCOL_HTTP = 1;
    public static final int SERVE_PROTOCOL_WEBDAV = 2;
    public static final int SERVE_PROTOCOL_FTP = 3;
    public static final int SERVE_PROTOCOL_DLNA = 4;

    public static final String RCLONE_CONFIG_NAME_KEY = "rclone_remote_name";
    private static volatile Boolean isCompatible;
    private static SafDAVServer safDAVServer;
    private Context context;
    private String rclone;
    private String rcloneConf;
    private final Map<Process, RcloneErrorCollector> errorCollectors =
            Collections.synchronizedMap(new WeakHashMap<>());

    public Rclone(Context context) {
        this.context = context;
        this.rclone = context.getApplicationInfo().nativeLibraryDir + "/librclone.so";
        this.rcloneConf = context.getFilesDir().getPath() + "/rclone.conf";
    }

    private String[] createCommand(ArrayList<String> args) {
        String[] command = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            command[i]= args.get(i);
        }
        return command;
    }
    private String[] createCommand(String ...args) {
        ArrayList<String> command = new ArrayList<>();

        command.add(rclone);
        command.add("--config");
        command.add(rcloneConf);
        command.addAll(Arrays.asList(args));
        return createCommand(command);
    }

    private String[] createCommandWithOptions(String ...args) {
        ArrayList<String> arguments = new ArrayList<String>(Arrays.asList(args));
        return createCommandWithOptions(arguments);
    }

    private String[] createCommandWithOptions(ArrayList<String> args) {
        ArrayList<String> command = new ArrayList<>();

        String cachePath = context.getCacheDir().getAbsolutePath();

        command.add(rclone);
        command.add("--cache-chunk-path");
        command.add(cachePath);
        command.add("--cache-db-path");
        command.add(cachePath);

        /*

        This fixed some bug. I dont know which one, but it breaks transfer of big files where
        the checksum needs to be calculated.
        This was probably due to some timeout for connecting misconfigured remotes.

        command.add("--low-level-retries");
        command.add("2");

        command.add("--timeout");
        command.add("5s");
        command.add("--contimeout");
        command.add("5s");
        */

        command.add("--config");
        command.add(rcloneConf);

        command.addAll(args);
        return createCommand(command);
    }

    public String[] getRcloneEnv(String... overwriteOptions) {
        ArrayList<String> environmentValues = new ArrayList<>();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);

        boolean proxyEnabled = pref.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        if(proxyEnabled) {
            String noProxy = pref.getString(context.getString(R.string.pref_key_no_proxy_hosts), "localhost");
            String protocol = pref.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
            String host = pref.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
            String user = pref.getString(context.getString(R.string.pref_key_proxy_username), "");
            String pass = pref.getString(context.getString(R.string.pref_key_proxy_password), "");
            int port = pref.getInt(context.getString(R.string.pref_key_proxy_port), 8080);
            String auth = "";
            if(!(user + pass).isEmpty()) {
                auth = user+":"+pass+"@";
            }
            String url = protocol + "://" + auth + host + ":" + port;
            // per https://golang.org/pkg/net/http/#ProxyFromEnvironment
            environmentValues.add("http_proxy=" + url);
            environmentValues.add("https_proxy=" + url);
            environmentValues.add("no_proxy=" + noProxy);
        }

        // if TMPDIR is not set, golang uses /data/local/tmp which is only
        // only accessible for the shell user
        String tmpDir = context.getCacheDir().getAbsolutePath();
        environmentValues.add("TMPDIR=" + tmpDir);

        // ignore chtimes errors
        // ref: https://github.com/rclone/rclone/issues/2446
        environmentValues.add("RCLONE_LOCAL_NO_SET_MODTIME=true");

        // Allow the caller to overwrite any option for special cases
        Iterator<String> envVarIter = environmentValues.iterator();
        while(envVarIter.hasNext()){
            String envVar = envVarIter.next();
            String optionName = envVar.substring(0, envVar.indexOf('='));
            for(String overwrite : overwriteOptions){
                if(overwrite.startsWith(optionName)) {
                    envVarIter.remove();
                    environmentValues.add(overwrite);
                }
            }
        }
        return environmentValues.toArray(new String[0]);
    }

    /**
     * Ensures stderr is consumed concurrently and records a failed rclone command when the
     * "Log rclone errors" preference is enabled. Draining is unconditional; the preference only
     * controls persistence to the sidebar log.
     */
    public void startErrorOutputDrainer(Process process, String operation) {
        startErrorOutputDrainer(process, operation, true);
    }

    private void startErrorOutputDrainer(Process process, String operation,
            boolean recordFailure) {
        if (process == null) {
            return;
        }
        synchronized (errorCollectors) {
            if (errorCollectors.containsKey(process)) {
                return;
            }
            long sequence = RCLONE_STDERR_SEQUENCE.incrementAndGet();
            String normalizedOperation = operation == null || operation.trim().isEmpty()
                    ? "command"
                    : operation.trim();
            RcloneErrorCollector collector = new RcloneErrorCollector(process, normalizedOperation, recordFailure);
            errorCollectors.put(process, collector);
            collector.start("rclone-stderr-" + sequence);
        }
    }

    public void logErrorOutput(Process process) {
        if (process == null) {
            return;
        }
        RcloneErrorCollector collector;
        synchronized (errorCollectors) {
            collector = errorCollectors.get(process);
        }
        if (collector == null) {
            startErrorOutputDrainer(process, "command");
            synchronized (errorCollectors) {
                collector = errorCollectors.get(process);
            }
        }
        if (collector != null) {
            collector.awaitCompletion();
            collector.logFailureIfNeeded();
        }
    }

    /** Returns the bounded stderr tail collected by a previously started drainer. */
    public String getCollectedErrorOutput(Process process) {
        if (process == null) {
            return "";
        }
        RcloneErrorCollector collector;
        synchronized (errorCollectors) {
            collector = errorCollectors.get(process);
        }
        if (collector == null) {
            startErrorOutputDrainer(process, "command");
            synchronized (errorCollectors) {
                collector = errorCollectors.get(process);
            }
        }
        return collector == null ? "" : collector.awaitCompletion();
    }

    private boolean isRcloneErrorLoggingEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                context.getString(R.string.pref_key_logs), false);
    }

    private final class RcloneErrorCollector implements Runnable {
        private Process process;
        private final String operation;
        private final boolean recordFailure;
        private final StringBuilder stderrTail = new StringBuilder();
        private final AtomicBoolean logged = new AtomicBoolean();
        private boolean truncated;
        private boolean finished;
        private int exitCode = Integer.MIN_VALUE;

        private RcloneErrorCollector(Process process, String operation, boolean recordFailure) {
            this.process = process;
            this.operation = operation;
            this.recordFailure = recordFailure;
        }

        private void start(String threadName) {
            Thread thread = new Thread(this, threadName);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            Process runningProcess = process;
            try (Reader reader = new InputStreamReader(runningProcess.getErrorStream(),
                    StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int charsRead;
                while ((charsRead = reader.read(buffer)) != -1) {
                    appendBounded(buffer, charsRead);
                }
            } catch (InterruptedIOException e) {
                FLog.i(TAG, "Rclone stderr drain interrupted for " + operation);
            } catch (IOException e) {
                if ("Stream closed".equals(e.getMessage())) {
                    FLog.d(TAG, "Rclone stderr stream already closed for " + operation);
                } else {
                    FLog.e(TAG, "Could not drain rclone stderr for " + operation, e);
                }
            } finally {
                try {
                    exitCode = runningProcess.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    try {
                        exitCode = runningProcess.exitValue();
                    } catch (IllegalThreadStateException ignored) {
                        exitCode = Integer.MIN_VALUE;
                    }
                }
                synchronized (this) {
                    finished = true;
                    process = null;
                    notifyAll();
                }
                logFailureIfNeeded();
            }
        }

        private void appendBounded(char[] buffer, int charsRead) {
            stderrTail.append(buffer, 0, charsRead);
            int overflow = stderrTail.length() - MAX_RCLONE_STDERR_CAPTURE_CHARS;
            if (overflow > 0) {
                stderrTail.delete(0, overflow);
                truncated = true;
            }
        }

        private String awaitCompletion() {
            boolean interrupted = false;
            synchronized (this) {
                while (!finished) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            String output = stderrTail.toString().trim();
            if (truncated && !output.isEmpty()) {
                output = "[Earlier stderr output truncated]\n" + output;
            }
            return output;
        }

        private void logFailureIfNeeded() {
            if (!recordFailure || exitCode == 0 || exitCode == Integer.MIN_VALUE
                    || !isRcloneErrorLoggingEnabled()
                    || !logged.compareAndSet(false, true)) {
                return;
            }
            String output = awaitCompletion();
            StringBuilder details = new StringBuilder()
                    .append("Exit code: ").append(exitCode);
            if (!output.isEmpty()) {
                details.append("\n\nstderr:\n").append(output);
            }
            String message = details.toString();
            SyncLog.rcloneErrorAsync(context, operation, message);
        }
    }

    @Nullable
    public List<FileItem> getDirectoryContent(RemoteItem remote, String path, boolean startAtRoot) {
        String remoteAndPath = remote.getName() + ":";
        if (startAtRoot) {
            remoteAndPath += "/";
        }
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isCrypt() && !remote.isAlias() && !remote.isCache())) {
            remoteAndPath += getLocalRemotePathPrefix(remote, context) + "/";
        }
        if (path.compareTo("//" + remote.getName()) != 0) {
            remoteAndPath += path;
        }
        // if SAFW, start emulation server
        if(remote.isRemoteType(RemoteItem.SAFW) && path.equals("//" + remote.getName()) && safDAVServer == null){
            try {
                safDAVServer = SafAccessProvider.getServer(context);
            } catch (IOException e) {
                // TODO: Provide port checking / alt port functionality
                FLog.e(TAG, "Cannot connect to SAF DAV emulation server");
                return null;
            }
        }

        String[] command;
        if (remote.isRemoteType(RemoteItem.LOCAL) || remote.isPathAlias()) {
            // ignore .android_secure errors
            // ref: https://github.com/rclone/rclone/issues/3179
            command = createCommandWithOptions("--ignore-errors", "lsjson", remoteAndPath);
        } else {
            command = createCommandWithOptions("lsjson", remoteAndPath);
        }
        String[] env = getRcloneEnv();
        JSONArray results;
        Process process;
        try {
            FLog.d(TAG, "getDirectoryContent[ENV]: %s", Arrays.toString(env));
            process = getRuntimeProcess(command, env);

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            process.waitFor();
            // For local/alias remotes, exit(6) is not a fatal error.
            if (process.exitValue() != 0 && (process.exitValue() != 6 || !remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.ALIAS))) {
                logErrorOutput(process);
                return null;
            }

            String outputStr = output.toString();
            results = new JSONArray(outputStr);

        } catch (InterruptedException e) {
            FLog.d(TAG, "getDirectoryContent: Aborted refreshing folder");
            return null;
        } catch (IOException | JSONException e) {
            FLog.e(TAG, "getDirectoryContent: Could not get folder content", e);
            return null;
        }

        List<FileItem> fileItemList = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            try {
                JSONObject jsonObject = results.getJSONObject(i);
                String filePath = (path.compareTo("//" + remote.getName()) == 0) ? "" : path + "/";
                filePath += jsonObject.getString("Path");
                String fileName = jsonObject.getString("Name");
                long fileSize = jsonObject.getLong("Size");
                String fileModTime = jsonObject.getString("ModTime");
                boolean fileIsDir = jsonObject.getBoolean("IsDir");
                String mimeType = jsonObject.getString("MimeType");

                if (remote.isCrypt()) {
                    String extension = fileName.substring(fileName.lastIndexOf(".") + 1);
                    String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (type != null) {
                        mimeType = type;
                    }
                }

                FileItem fileItem = new FileItem(remote, filePath, fileName, fileSize, fileModTime, mimeType, fileIsDir, startAtRoot);
                fileItemList.add(fileItem);
            } catch (JSONException e) {
                FLog.e(TAG, "getDirectoryContent: Could not decode JSON", e);
                return null;
            }
        }
        return fileItemList;
    }

    public List<RemoteItem> getRemotes() {
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process;
        JSONObject remotesJSON;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> pinnedRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_pinned_remotes), new HashSet<>());
        Set<String> favoriteRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());

        try {
            process = getRuntimeProcess(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            process.waitFor();
            if (process.exitValue() != 0) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                logErrorOutput(process);
                return new ArrayList<>();
            }

            remotesJSON = new JSONObject(output.toString());
        } catch (IOException | InterruptedException | JSONException e) {
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
            return new ArrayList<>();
        }

        List<RemoteItem> remoteItemList = new ArrayList<>();
        Iterator<String> iterator = remotesJSON.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            try {
                JSONObject remoteJSON = new JSONObject(remotesJSON.get(key).toString());
                String type = remoteJSON.optString("type");
                if (type.trim().isEmpty()) {
                    Toasty.error(context, context.getResources().getString(R.string.error_retrieving_remote, key), Toast.LENGTH_SHORT, true).show();
                    continue;
                }
                if(type.equals("webdav")){
                    String url = remoteJSON.optString("url");
                    if(url.startsWith(SafConstants.SAF_REMOTE_URL)){
                        type = SafConstants.SAF_REMOTE_NAME;
                    }
                }

                RemoteItem newRemote = new RemoteItem(key, type);
                if (isWrappingRemoteType(type)) {
                    // Keep the wrapper remote visible even when its backing target cannot be
                    // resolved from rclone.conf. Valid rclone targets include relative local
                    // paths, on-the-fly backends (":local:/path") and named remotes with
                    // connection-string overrides ("remote,param=value:path"). The old parser
                    // rejected these forms, returned null and made a successfully-created crypt
                    // remote disappear from the UI with an "Error retrieving remote" toast.
                    newRemote = getRemoteType(remotesJSON, newRemote, key, 8, new HashSet<>());
                }

                if (pinnedRemotes.contains(newRemote.getName())) {
                    newRemote.pin(true);
                }

                if (favoriteRemotes.contains(newRemote.getName())) {
                    newRemote.setDrawerPinned(true);
                }

                remoteItemList.add(newRemote);
            } catch (JSONException e) {
                FLog.e(TAG, "getRemotes: error decoding remotes", e);
                return new ArrayList<>();
            }
        }

        return remoteItemList;
    }

    public RemoteItem getRemoteItemFromName(String remoteName) {
        List<RemoteItem> remoteItemList = getRemotes();
        for (RemoteItem remoteItem : remoteItemList) {
            if (remoteItem.getName().equals(remoteName)) {
                return remoteItem;
            }
        }
        return null;
    }


    private Process getRuntimeProcess(String[] command) throws IOException {
        return getRuntimeProcess(command, new String[0]);
    }

    /** Starts an rclone process and immediately drains stderr on a dedicated daemon thread. */
    private Process getRuntimeProcess(String[] command, String[] env) throws IOException {
        Process process = getRuntimeProcessRaw(command, env);
        startErrorOutputDrainer(process, describeRcloneOperation(command));
        return process;
    }

    private Process getRuntimeProcessWithoutFailureLog(String[] command) throws IOException {
        Process process = getRuntimeProcessRaw(command);
        startErrorOutputDrainer(process, describeRcloneOperation(command), false);
        return process;
    }

    /**
     * Starts a process without consuming stderr. Use only when the caller must parse stderr live
     * (for example JSON transfer progress, OAuth prompts, or an interactive config recipe).
     */
    private Process getRuntimeProcessRaw(String[] command, String[] env) throws IOException {
        try {
            return Runtime.getRuntime().exec(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "Error executing rclone", e);
            throw new IOException("Error executing rclone: " + e.getMessage(), e);
        }
    }

    private Process getRuntimeProcessRaw(String[] command) throws IOException {
        return getRuntimeProcessRaw(command, new String[0]);
    }

    private String describeRcloneOperation(String[] command) {
        for (String argument : command) {
            if ("--version".equals(argument)) {
                return "version";
            }
            switch (argument) {
                case "lsjson":
                case "config":
                case "obscure":
                case "serve":
                case "sync":
                case "copy":
                case "purge":
                case "deletefile":
                case "mkdir":
                case "moveto":
                case "cat":
                case "rcat":
                case "cleanup":
                case "link":
                case "md5sum":
                case "sha1sum":
                case "about":
                case "listremotes":
                    return argument;
                default:
                    break;
            }
        }
        return "command";
    }

    private boolean isWrappingRemoteType(String type) {
        return "crypt".equals(type) || "alias".equals(type) || "cache".equals(type);
    }

    /**
     * Resolves the effective backend type of wrapper remotes while preserving the wrapper flags.
     *
     * <p>Failure to resolve a backing remote must never remove the wrapper from the remote list.
     * rclone accepts more target forms than a simple {@code name:path} reference, including local
     * relative paths, on-the-fly backends and connection-string overrides. It can also keep a
     * temporarily broken remote in the config. In all of those cases the UI should still show the
     * configured remote and let the actual rclone operation report a useful error when opened.</p>
     */
    private RemoteItem getRemoteType(JSONObject remotesJSON, RemoteItem remoteItem,
                                     String remoteName, int maxDepth, Set<String> visited) {
        if (remoteName == null || remoteName.trim().isEmpty()) {
            return remoteItem;
        }

        if (maxDepth < 0 || !visited.add(remoteName)) {
            FLog.w(TAG, "getRemoteType: wrapper cycle or maximum depth reached for %s", remoteName);
            return remoteItem;
        }

        JSONObject remoteJSON = remotesJSON.optJSONObject(remoteName);
        if (remoteJSON == null) {
            FLog.w(TAG, "getRemoteType: backing remote %s is not present in config", remoteName);
            return remoteItem;
        }

        String type = remoteJSON.optString("type").trim();
        if (type.isEmpty()) {
            FLog.w(TAG, "getRemoteType: remote %s has no backend type", remoteName);
            return remoteItem;
        }

        switch (type) {
            case "crypt":
                remoteItem.setIsCrypt(true);
                break;
            case "alias":
                remoteItem.setIsAlias(true);
                break;
            case "cache":
                remoteItem.setIsCache(true);
                break;
            default:
                remoteItem.setType(type);
                return remoteItem;
        }

        String target = remoteJSON.optString("remote").trim();
        if (target.isEmpty()) {
            FLog.w(TAG, "getRemoteType: wrapper remote %s has no backing target", remoteName);
            return remoteItem;
        }

        RemoteReferenceParser.Result reference = RemoteReferenceParser.parse(target);
        switch (reference.getKind()) {
            case LOCAL:
                remoteItem.setType("local");
                remoteItem.setIsPathAlias(true);
                return remoteItem;
            case INLINE_BACKEND:
                // There is no config section to recurse into for connection strings such as
                // ":local:/storage/emulated/0", so use the inline backend directly.
                applyInlineRemoteType(remoteItem, reference.getValue());
                return remoteItem;
            case NAMED_REMOTE:
                String referencedRemote = reference.getValue();
                if (!remotesJSON.has(referencedRemote)) {
                    // Preserve the crypt/alias/cache item in the list. The backing remote may be
                    // supplied through environment configuration or may be temporarily missing.
                    FLog.w(TAG, "getRemoteType: backing remote %s referenced by %s is unavailable",
                            referencedRemote, remoteName);
                    return remoteItem;
                }
                return getRemoteType(
                        remotesJSON, remoteItem, referencedRemote, maxDepth - 1, visited);
            case UNKNOWN:
            default:
                FLog.w(TAG, "getRemoteType: could not parse backing target for %s", remoteName);
                return remoteItem;
        }
    }

    private void applyInlineRemoteType(RemoteItem remoteItem, String type) {
        switch (type) {
            case "crypt":
                remoteItem.setIsCrypt(true);
                break;
            case "alias":
                remoteItem.setIsAlias(true);
                break;
            case "cache":
                remoteItem.setIsCache(true);
                break;
            case "local":
                remoteItem.setType("local");
                remoteItem.setIsPathAlias(true);
                break;
            default:
                remoteItem.setType(type);
                break;
        }
    }

    @Nullable
    public Process configCreate(List<String> options) {
        return configCreate(options, false);
    }

    /**
     * Starts config create without attaching the generic stderr drainer. OAuth setup owns stderr
     * so it can discover the authorization URL while rclone is still running.
     */
    @Nullable
    public Process configCreateInteractive(List<String> options) {
        return configCreate(options, true);
    }

    private Process configCreate(List<String> options, boolean callerReadsStderr) {
        // https://rclone.org/commands/rclone_config_create/
        // See the NB-comment why we need to pass --obscure.
        // Otherwise long passwords fail.
        options.add("--obscure");
        return config("create", options, callerReadsStderr);
    }

    @Nullable
    public Process configUpdate(List<String> options) {
        return configCreate(options);
    }

    public Process config(String task, List<String> options) {
        return config(task, options, false);
    }

    private Process config(String task, List<String> options, boolean callerReadsStderr) {
        String[] command = createCommand("config", task);
        String[] opt = options.toArray(new String[0]);
        String[] commandWithOptions = new String[command.length + options.size()];

        System.arraycopy(command, 0, commandWithOptions, 0, command.length);

        System.arraycopy(opt, 0, commandWithOptions, command.length, opt.length);

        try {
            return callerReadsStderr
                    ? getRuntimeProcessRaw(commandWithOptions)
                    : getRuntimeProcess(commandWithOptions);
        } catch (IOException e) {
            FLog.e(TAG, "configCreate: error starting rclone", e);
            return null;
        }
    }

    @Nullable
    public HashMap<String, String> getConfig(String name) {
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process;
        JSONObject configs = new JSONObject();

        HashMap<String, String> options = new HashMap<>();

        try {
            process = getRuntimeProcess(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            process.waitFor();
            if (process.exitValue() != 0) {
                Toasty.error(context, context.getString(R.string.error_getting_config), Toast.LENGTH_SHORT, true).show();
                logErrorOutput(process);
            }

            configs = new JSONObject(output.toString());
        } catch (IOException | InterruptedException | JSONException e) {
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
        }

        JSONObject selectedConfig = configs.optJSONObject(name);
        Iterator<String> keys = selectedConfig.keys();

        while(keys.hasNext()) {
            String key = keys.next();
            options.put(key,  selectedConfig.optString(key));
        }

        options.put(RCLONE_CONFIG_NAME_KEY,  name);
        return options;
        
    }

    public Process configInteractive() throws IOException {
        String[] command = createCommand("config");
        String[] environment = getRcloneEnv();
        return getRuntimeProcessRaw(command, environment);
    }

    public void deleteRemote(String remoteName) {
        String[] command = createCommandWithOptions("config", "delete", remoteName);
        Process process;

        try {
            process = getRuntimeProcess(command);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "deleteRemote: error starting rclone", e);
        }
    }

    public String obscure(String pass) {
        String[] command = createCommand("obscure", pass);

        Process process;
        try {
            process = getRuntimeProcess(command);
            process.waitFor();
            if (process.exitValue() != 0) {
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return  reader.readLine();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "obscure: error starting rclone", e);
            // TODO: guard callers against null result
            return null;
        }
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
                         @Nullable String password, @NonNull RemoteItem remote, @Nullable String servePath,
                         @Nullable String baseUrl, boolean readOnly) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, baseUrl,
                readOnly, false);
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
                         @Nullable String password, @NonNull RemoteItem remote, @Nullable String servePath,
                         @Nullable String baseUrl, boolean readOnly, boolean useVideoStreamReadConfig) {
        String remoteName = remote.getName();
        String localRemotePath = (remote.isRemoteType(RemoteItem.LOCAL)) ? getLocalRemotePathPrefix(remote, context)  + "/" : "";
        String path = (servePath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath : remoteName + ":" + localRemotePath + servePath;
        String address;
        String commandProtocol;

        switch (protocol) {
            case SERVE_PROTOCOL_HTTP:
                commandProtocol = "http";
                break;
            case SERVE_PROTOCOL_FTP:
                commandProtocol = "ftp";
                break;
            case SERVE_PROTOCOL_DLNA:
                commandProtocol = "dlna";
                break;
            default:
                commandProtocol = "webdav";
        }

        if (allowRemoteAccess) {
            address = ":" + String.valueOf(port);
        } else {
            address = "127.0.0.1:" + String.valueOf(port);
        }

        ArrayList<String> params = new ArrayList<>(Arrays.asList(
                createCommandWithOptions("serve", commandProtocol, "--addr", address, path)));

        if (readOnly) {
            params.add("--read-only");
        }

        // Keep the video player's remote reads small and sequential. This is deliberately opt-in so
        // manually started HTTP/FTP/WebDAV/DLNA servers and audio streams retain their existing
        // behaviour.
        if (protocol == SERVE_PROTOCOL_HTTP && useVideoStreamReadConfig) {
            params.add("--vfs-read-chunk-size");
            params.add("10M");
            params.add("--vfs-read-chunk-size-limit");
            params.add("0");
            params.add("--vfs-read-chunk-streams");
            params.add("0");
        }

        if(null != user && user.length() > 0) {
            params.add("--user");
            params.add(user);
        }

        if(null != password && password.length() > 0) {
            params.add("--pass");
            params.add(password);
        }

        if(null != baseUrl && baseUrl.length() > 0) {
            params.add("--baseurl");
            params.add(baseUrl);
        }

        String[] env = getRcloneEnv();
        String[] command = params.toArray(new String[0]);
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "serve: error starting rclone", e);
            // todo: guard callers against null result
            return null;
        }
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, String user, String password, RemoteItem remote, String servePath) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, null, false);
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, String user, String password,
                         RemoteItem remote, String servePath, boolean readOnly) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, null, readOnly);
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
                         @Nullable String password, @NonNull RemoteItem remote, @Nullable String servePath,
                         @Nullable String baseUrl) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, baseUrl, false);
    }

    /**
     * This is only kept for legacy purposes. It was used before md5-checksum was introduced.
     * @param remoteItem
     * @param localPath
     * @param remotePath
     * @param syncDirection
     * @return
     */
    @Deprecated
    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection) {
        return sync(remoteItem, localPath, remotePath, syncDirection, false);
    }

    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum) {
        String[] command;
        String remoteName = remoteItem.getName();
        String localRemotePath = (remoteItem.isRemoteType(RemoteItem.LOCAL)) ? getLocalRemotePathPrefix(remoteItem, context)  + "/" : "";
        String remoteSection = (remotePath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath : remoteName + ":" + localRemotePath + remotePath;

        ArrayList<String> defaultParameter = new ArrayList<>(Arrays.asList("--transfers", "1", "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"));
        ArrayList<String> directionParameter = new ArrayList<>();

        if(useMD5Sum){
            defaultParameter.add("--checksum");
        }

        if (syncDirection == SyncDirectionObject.SYNC_LOCAL_TO_REMOTE) {
            Collections.addAll(directionParameter, "sync", localPath, remoteSection);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        } else if (syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL) {
            Collections.addAll(directionParameter, "sync", remoteSection, localPath);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        } else if (syncDirection == SyncDirectionObject.COPY_LOCAL_TO_REMOTE) {
            Collections.addAll(directionParameter, "copy", localPath, remoteSection);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        }else if (syncDirection == SyncDirectionObject.COPY_REMOTE_TO_LOCAL) {
            Collections.addAll(directionParameter, "copy", remoteSection, localPath);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        }else {
            return null;
        }

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcessRaw(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "sync: error starting rclone", e);
            return null;
        }
    }

    public Process downloadFile(RemoteItem remote, FileItem downloadItem, String downloadPath) {
        String[] command;
        String remoteFilePath;
        String localFilePath;

        remoteFilePath = remote.getName() + ":";
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            remoteFilePath += getLocalRemotePathPrefix(remote, context)  + "/";
        }
        remoteFilePath += downloadItem.getPath();

        if (downloadItem.isDir()) {
            localFilePath = downloadPath + "/" + downloadItem.getName();
        } else {
            localFilePath = downloadPath;
        }

        localFilePath = encodePath(localFilePath);

        command = createCommandWithOptions("copy", remoteFilePath, localFilePath, "--transfers", "1", "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcessRaw(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "downloadFile: error starting rclone", e);
            return null;
        }
    }

    public Process uploadFile(RemoteItem remote, String uploadPath, String uploadFile) {
        String remoteName = remote.getName();
        String path;
        String[] command;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        File file = new File(uploadFile);
        if (file.isDirectory()) {
            int index = uploadFile.lastIndexOf('/');
            String dirName = uploadFile.substring(index + 1);
            path = (uploadPath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath + dirName : remoteName + ":" + localRemotePath + uploadPath + "/" + dirName;
        } else {
            path = (uploadPath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath : remoteName + ":" + localRemotePath + uploadPath;
        }

        command = createCommandWithOptions("copy", uploadFile, path, "--transfers", "1", "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcessRaw(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "uploadFile: error starting rclone", e);
            return null;
        }

    }

    // Can't pass \u0000 as cmd arg - encode like rclone with U+2400
    // Ref: Appcenter #22305285
    // TODO Appcenter #170195533 - rclone serve
    @NonNull
    private String encodePath(String localFilePath) {
        if (localFilePath.indexOf('\u0000') < 0) {
            return localFilePath;
        }
        StringBuilder localPathBuilder = new StringBuilder(localFilePath.length());
        for (char c : localFilePath.toCharArray()) {
            if (c == '\u0000') {
                localPathBuilder.append('\u2400');
            } else {
                localPathBuilder.append(c);
            }

        }
        return localPathBuilder.toString();
    }

    public Process deleteItems(RemoteItem remote, FileItem deleteItem) {
        String[] command;
        String filePath;
        Process process = null;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        filePath = remote.getName() + ":" + localRemotePath + deleteItem.getPath();
        if (deleteItem.isDir()) {
            command = createCommandWithOptions("purge", filePath);
        } else {
            command = createCommandWithOptions("deletefile", filePath);
        }

        String[] env = getRcloneEnv();
        try {
            process = getRuntimeProcessRaw(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "deleteItems: error starting rclone", e);
        }
        return process;
    }

    public Boolean makeDirectory(RemoteItem remote, String path) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String newDir = remote.getName() + ":" + localRemotePath + path;
        String[] command = createCommandWithOptions("mkdir", newDir);
        String[] env = getRcloneEnv();
        try {
            Process process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "makeDirectory: error running rclone", e);
            return false;
        }
        return true;
    }

    public Process moveTo(RemoteItem remote, FileItem moveItem, String newLocation) {
        String remoteName = remote.getName();
        String[] command;
        String oldFilePath;
        String newFilePath;
        Process process = null;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        oldFilePath = remoteName + ":" + localRemotePath + moveItem.getPath();
        newFilePath = (newLocation.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath + moveItem.getName() : remoteName + ":" + localRemotePath + newLocation + "/" + moveItem.getName();
        command = createCommandWithOptions("moveto", oldFilePath, newFilePath);
        String[] env = getRcloneEnv();
        try {
            process = getRuntimeProcessRaw(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "moveTo: error starting rclone", e);
        }

        return process;
    }

    public Boolean moveTo(RemoteItem remote, String oldFile, String newFile) {
        String remoteName = remote.getName();
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String oldFilePath = remoteName + ":" + localRemotePath + oldFile;
        String newFilePath = remoteName + ":" + localRemotePath + newFile;
        String[] command = createCommandWithOptions("moveto", oldFilePath, newFilePath);
        String[] env = getRcloneEnv();
        try {
            Process process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "moveTo: error running rclone", e);
            return false;
        }
        return true;
    }

    /**
     * Builds the canonical rclone path for a remote file. This is also used by
     * the thumbnail pipeline so media can be streamed without exposing a local
     * HTTP server.
     */
    public String getRemoteFilePath(RemoteItem remote, FileItem fileItem) {
        StringBuilder remoteFilePath = new StringBuilder(remote.getName()).append(':');
        if (remote.isRemoteType(RemoteItem.LOCAL)
                && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            remoteFilePath.append(getLocalRemotePathPrefix(remote, context)).append('/');
        }
        remoteFilePath.append(fileItem.getPath());
        return remoteFilePath.toString();
    }

    public InputStream downloadToPipe(String rclonePath) throws IOException {
        return downloadToPipe(rclonePath, 0L);
    }

    /**
     * Streams a complete remote file and optionally aborts when rclone produces no bytes for the
     * configured idle interval. Every successful read resets the idle deadline.
     */
    public InputStream downloadToPipe(String rclonePath, long idleTimeoutMs) throws IOException {
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be >= 0");
        }
        return startCatProcess(createCommandWithOptions("cat", rclonePath), idleTimeoutMs);
    }

    /**
     * Streams a byte range from a remote file. This is used by seekable media
     * consumers such as {@code MediaDataSource}: callers can request only the
     * container metadata or encoded samples that Android asks for instead of
     * downloading a complete video first.
     */
    public InputStream downloadRangeToPipe(String rclonePath, long offset, long count)
            throws IOException {
        return downloadRangeToPipe(rclonePath, offset, count, 0L);
    }

    /**
     * Streams a byte range and optionally owns the rclone process with an idle watchdog.
     * Closing the returned stream also terminates a still-running process, which prevents a
     * cancelled or stalled media probe from leaving an orphaned {@code rclone cat} behind.
     */
    public InputStream downloadRangeToPipe(String rclonePath, long offset, long count,
            long idleTimeoutMs) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be >= 0");
        }
        return startCatProcess(createCommandWithOptions(
                "cat",
                rclonePath,
                "--offset",
                String.valueOf(offset),
                "--count",
                String.valueOf(count)), idleTimeoutMs);
    }

    private InputStream startCatProcess(String[] command) throws IOException {
        return startCatProcess(command, 0L);
    }

    private InputStream startCatProcess(String[] command, long idleTimeoutMs) throws IOException {
        String[] env = getRcloneEnv();
        final Process process = getRuntimeProcessRaw(command, env);
        long streamId = RCLONE_CAT_STREAM_SEQUENCE.incrementAndGet();
        startErrorOutputDrainer(process, "cat");

        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                FLog.e(TAG, "downloadToPipe: error waiting for process", e);
            }
        }, "rclone-cat-waiter-" + streamId);
        waiter.setDaemon(true);
        waiter.start();

        InputStream input = process.getInputStream();
        return idleTimeoutMs > 0L
                ? new ManagedProcessInputStream(input, process, idleTimeoutMs)
                : input;
    }


    private static final class ManagedProcessInputStream extends FilterInputStream {
        private final Process process;
        private final long idleTimeoutNanos;
        private final Object watchdogLock = new Object();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final Thread watchdog;
        private long lastActivityNanos;

        private ManagedProcessInputStream(InputStream input, Process process, long idleTimeoutMs) {
            super(input);
            this.process = process;
            this.idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs);
            this.lastActivityNanos = System.nanoTime();
            this.watchdog = new Thread(this::watchForIdleTimeout, "rclone-cat-idle-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        @Override
        public int read() throws IOException {
            int value;
            try {
                value = super.read();
            } catch (IOException failure) {
                if (timedOut.get()) {
                    throw timeoutException(failure);
                }
                throw failure;
            }
            throwIfTimedOut();
            if (value >= 0) {
                recordActivity();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read;
            try {
                read = super.read(buffer, offset, length);
            } catch (IOException failure) {
                if (timedOut.get()) {
                    throw timeoutException(failure);
                }
                throw failure;
            }
            throwIfTimedOut();
            if (read > 0) {
                recordActivity();
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            closeInternal(false);
        }

        private void watchForIdleTimeout() {
            while (!closed.get()) {
                long remainingNanos;
                synchronized (watchdogLock) {
                    if (closed.get()) {
                        return;
                    }
                    long idleNanos = System.nanoTime() - lastActivityNanos;
                    remainingNanos = idleTimeoutNanos - idleNanos;
                    if (remainingNanos > 0L) {
                        long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                        int waitNanos = (int) (remainingNanos
                                - TimeUnit.MILLISECONDS.toNanos(waitMillis));
                        try {
                            watchdogLock.wait(waitMillis, waitNanos);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                }
                closeInternal(true);
                return;
            }
        }

        private void recordActivity() {
            synchronized (watchdogLock) {
                if (closed.get() || timedOut.get()) {
                    return;
                }
                lastActivityNanos = System.nanoTime();
                watchdogLock.notifyAll();
            }
        }

        private void throwIfTimedOut() throws IOException {
            if (timedOut.get()) {
                throw timeoutException(null);
            }
        }

        private InterruptedIOException timeoutException(@Nullable IOException cause) {
            InterruptedIOException timeout = new InterruptedIOException(
                    "Timed out after "
                            + TimeUnit.NANOSECONDS.toMillis(idleTimeoutNanos)
                            + " ms without data from rclone");
            if (cause != null) {
                timeout.initCause(cause);
            }
            return timeout;
        }

        private void closeInternal(boolean dueToTimeout) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (dueToTimeout) {
                timedOut.set(true);
            }
            synchronized (watchdogLock) {
                watchdogLock.notifyAll();
            }
            if (Thread.currentThread() != watchdog) {
                watchdog.interrupt();
            }
            process.destroy();
            try {
                in.close();
            } catch (IOException ignored) {
                // A blocked reader observes the timeout flag and receives InterruptedIOException.
            }
        }
    }

    public OutputStream uploadFromPipe(String rclonePath) throws IOException {
        String[] command = createCommandWithOptions("rcat", rclonePath, "--streaming-upload-cutoff", "500K");
        String[] env = getRcloneEnv();
        final Process process = getRuntimeProcess(command, env);
        new Thread() {
            @Override
            public void run() {
                try {
                    process.waitFor();
                    logErrorOutput(process);
                } catch (InterruptedException e) {
                    FLog.e(TAG, "uploadFromPipe: error waiting for process", e);
                }
            }
        }.start();
        return process.getOutputStream();
    }

    public boolean emptyTrashCan(String remote) {
        String[] command = createCommandWithOptions("cleanup", remote + ":");
        Process process = null;
        String[] env = getRcloneEnv();
        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "emptyTrashCan: error running rclone", e);
        }

        return process != null && process.exitValue() == 0;
    }

    public String link(RemoteItem remote, String filePath) {
        String linkPath = remote.getName() + ":";
        linkPath += (remote.isRemoteType(RemoteItem.LOCAL)) ? getLocalRemotePathPrefix(remote, context) + "/" : "";
        if (!filePath.equals("//" + remote.getName())) {
            linkPath += filePath;
        }
        String[] command = createCommandWithOptions("link", linkPath);
        Process process = null;
        String[] env = getRcloneEnv();

        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             return reader.readLine();

        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "link: error running rclone", e);
            if (process != null) {
                logErrorOutput(process);
            }
        }
        return null;
    }

    public String calculateMD5(RemoteItem remote, FileItem fileItem) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String remoteAndPath = remote.getName() + ":" + localRemotePath + fileItem.getName();
        String[] command = createCommandWithOptions("md5sum", remoteAndPath);
        String[] env = getRcloneEnv();
        Process process;
        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                return context.getString(R.string.hash_error);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            String[] split = line.split("\\s+");
            if (split[0].trim().isEmpty()) {
                return context.getString(R.string.hash_unsupported);
            } else {
                return split[0];
            }
        } catch (IOException e) {
            FLog.e(TAG, "calculateMD5: error running rclone", e);
            return context.getString(R.string.hash_error);
        } catch (InterruptedException e) {
            FLog.v(TAG, "calculateMD5: calculation stopped");
            return context.getString(R.string.hash_error);
        }
    }

    public String calculateSHA1(RemoteItem remote, FileItem fileItem) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String remoteAndPath = remote.getName() + ":" + localRemotePath + fileItem.getName();
        String[] command = createCommandWithOptions("sha1sum", remoteAndPath);
        String[] env = getRcloneEnv();
        Process process;
        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                return context.getString(R.string.hash_error);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            String[] split = line.split("\\s+");
            if (split[0].trim().isEmpty()) {
                return context.getString(R.string.hash_unsupported);
            } else {
                return split[0];
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "calculateSHA1: error running rclone", e);
            return context.getString(R.string.hash_error);
        }
    }

    public String getRcloneVersion() {
        String[] command = createCommand("--version");
        ArrayList<String> result = new ArrayList<>();
        try {
            Process process = getRuntimeProcess(command);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return "-1";
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "getRcloneVersion: error running rclone", e);
            return "-1";
        }

        String[] version = result.get(0).split("\\s+");
        return version[1];
    }

    public Process reconnectRemote(RemoteItem remoteItem) {
        String remoteName = remoteItem.getName() + ':';
        String[] command = createCommand("config", "reconnect", remoteName);

        try {
            return getRuntimeProcessRaw(command, getRcloneEnv());
        } catch (IOException e) {
            return null;
        }
    }

    public AboutResult aboutRemote(RemoteItem remoteItem) {
        String remoteName = remoteItem.getName() + ':';
        String[] command = createCommand("about", "--json", remoteName);
        StringBuilder output = new StringBuilder();
        AboutResult stats;
        Process process;
        JSONObject aboutJSON;

        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor();
            if (0 != process.exitValue()) {
                FLog.e(TAG, "aboutRemote: rclone error, exit(%d)", process.exitValue());
                FLog.e(TAG, "aboutRemote: ", output);
                logErrorOutput(process);
                return new AboutResult();
            }

            aboutJSON = new JSONObject(output.toString());
        } catch (IOException | InterruptedException | JSONException e) {
            FLog.e(TAG, "aboutRemote: unexpected error", e);
            return new AboutResult();
        }

        try {
            stats = new AboutResult(
                    aboutJSON.opt("used") != null ? aboutJSON.getLong("used") : -1,
                    aboutJSON.opt("total") != null ? aboutJSON.getLong("total") : -1,
                    aboutJSON.opt("free") != null ? aboutJSON.getLong("free") : -1,
                    aboutJSON.opt("trashed") != null ? aboutJSON.getLong("trashed") : -1
            );
        } catch (JSONException e) {
            FLog.e(TAG, "aboutRemote: JSON format error ", e);
            return new AboutResult();
        }

        return stats;
    }

    public class AboutResult {
        private final long used;
        private final long total;
        private final long free;
        private final long trashed;
        private boolean failed;

        public AboutResult(long used, long total, long free, long trashed) {
            this.used = used;
            this.total = total;
            this.free = free;
            this.trashed = trashed;
            this.failed = false;
        }

        public AboutResult () {
            this(-1, -1, -1,  -1);
            this.failed = true;
        }

        public long getUsed() {
            return used;
        }

        public long getTotal() {
            return total;
        }

        public long getFree() {
            return free;
        }

        public long getTrashed() {
            return trashed;
        }

        public boolean hasFailed(){
            return failed;
        }
    }

    public Boolean isConfigEncrypted() {
        if (!isConfigFileCreated()) {
            return false;
        }
        String[] command = createCommand( "--ask-password=false", "listremotes");
        Process process;
        try {
            process = getRuntimeProcessWithoutFailureLog(command);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "Error running rclone %s", e, Arrays.toString(command));
            return false;
        }
        return process.exitValue() != 0;
    }

    public Boolean decryptConfig(String password) {
        String[] command = createCommand("--ask-password=false", "config", "show");
        String[] environmentalVars = {"RCLONE_CONFIG_PASS=" + password};
        Process process;

        try {
            process = getRuntimeProcess(command, environmentalVars);
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error running rclone", e);
            return false;
        }

        ArrayList<String> result = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error copying rclone stdout", e);
            return false;
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            FLog.e(TAG, "decryptConfig: error waiting for rclone", e);
            return false;
        }

        if (process.exitValue() != 0) {
            return false;
        }

        String appsFileDir = context.getFilesDir().getPath();
        File file = new File(appsFileDir, "rclone.conf");

        try {
            file.delete();
            file.createNewFile();
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream);
            for (String line2 : result) {
                outputStreamWriter.append(line2);
                outputStreamWriter.append("\n");
            }
            outputStreamWriter.close();
            fileOutputStream.flush();
            fileOutputStream.close();
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error reading stdout", e);
            return false;
        }
        return true;
    }

    public boolean isConfigFileCreated() {
        String appsFileDir = context.getFilesDir().getPath();
        String configFile = appsFileDir + "/rclone.conf";
        File file = new File(configFile);
        return file.exists();
    }

    // on all devices, look under ./Android/data/ca.pkay.rcloneexplorer/files/rclone.conf
    public Uri searchExternalConfig(){
        File[] extDir = context.getExternalFilesDirs(null);
        for(File dir : extDir){
            File file = new File(dir + "/rclone.conf");
            if(file.exists() && isValidConfig(file.getAbsolutePath())){
                return Uri.fromFile(file);
            }
        }
        return null;
    }

    public boolean isZipPackage(Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return false;
            byte[] signature = new byte[4];
            int read = input.read(signature);
            return read == 4
                    && signature[0] == 'P'
                    && signature[1] == 'K'
                    && ((signature[2] == 3 && signature[3] == 4)
                    || (signature[2] == 5 && signature[3] == 6)
                    || (signature[2] == 7 && signature[3] == 8));
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    public File getFileFromZip(Uri uri, String target, File targetFile) throws IOException {
        InputStream rawInput;
        try {
            rawInput = context.getContentResolver().openInputStream(uri);
        } catch (NullPointerException error) {
            throw new IOException(error);
        }
        if (rawInput == null) {
            throw new IOException("Unable to open configuration package");
        }

        try (InputStream input = rawInput;
             ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry zipEntry;
            byte[] buffer = new byte[8192];
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (!zipEntry.isDirectory() && zipEntry.getName().equals(target)) {
                    targetFile.getParentFile().mkdirs();
                    try (FileOutputStream output = new FileOutputStream(targetFile)) {
                        int count;
                        while ((count = zipInput.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                        output.flush();
                    }
                    zipInput.closeEntry();
                    return targetFile;
                }
                zipInput.closeEntry();
            }
        }
        targetFile.delete();
        return null;
    }

    public String readDatabaseJson(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.json-tmp", "rcx.json");
    }

    public String readSharedPrefs(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.prefs-tmp", "rcx.prefs");
    }

    @Nullable
    public String readQuarkDavJson(Uri uri) throws Exception {
        // Legacy Round Sync backups predate QuarkDav. A missing entry is
        // represented by null so importing an old package preserves any
        // QuarkDav configuration already present on this device.
        return readOptionalTextfileFromZip(
                uri,
                "quarkdav.json-tmp",
                "quarkdav.json",
                null);
    }

    public String readTextfileFromZip(Uri uri, String tempFileName, String targetFileName)
            throws Exception {
        File temp = new File(context.getFilesDir(), tempFileName);
        File extracted = getFileFromZip(uri, targetFileName, temp);
        if (extracted == null) {
            throw new IOException("Missing " + targetFileName + " in configuration package");
        }
        try {
            return readUtf8File(extracted);
        } finally {
            extracted.delete();
        }
    }

    private String readOptionalTextfileFromZip(
            Uri uri,
            String tempFileName,
            String targetFileName,
            String defaultValue) throws Exception {
        File temp = new File(context.getFilesDir(), tempFileName);
        File extracted = getFileFromZip(uri, targetFileName, temp);
        if (extracted == null) {
            return defaultValue;
        }
        try {
            return readUtf8File(extracted);
        } finally {
            extracted.delete();
        }
    }

    private String readUtf8File(File file) throws IOException {
        char[] buffer = new char[4096];
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            int count;
            while ((count = reader.read(buffer, 0, buffer.length)) > 0) {
                content.append(buffer, 0, count);
            }
        }
        return content.toString();
    }

    /** Validates the packaged rclone.conf without changing the active configuration. */
    public boolean validateConfigFileFromZip(Uri uri) throws IOException {
        File tempFile = new File(context.getFilesDir(), "rclone.conf-validate-tmp");
        File extracted = getFileFromZip(uri, "rclone.conf", tempFile);
        if (extracted == null) {
            throw new IOException("Missing rclone.conf in configuration package");
        }
        try {
            return isValidConfig(extracted.getAbsolutePath());
        } finally {
            extracted.delete();
        }
    }

    public boolean copyConfigFileFromZip(Uri uri) throws Exception {
        File tempFile = new File(context.getFilesDir(), "rclone.conf-tmp");
        File configFile = new File(context.getFilesDir(), "rclone.conf");
        File extracted = getFileFromZip(uri, "rclone.conf", tempFile);
        if (extracted == null) {
            throw new IOException("Missing rclone.conf in configuration package");
        }
        try {
            if (!isValidConfig(extracted.getAbsolutePath())) {
                return false;
            }
            replaceFileSafely(extracted, configFile);
            return true;
        } finally {
            extracted.delete();
        }
    }

    private void copyFile(File source, File target) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        }
    }

    /** Replaces a config in the same directory and restores the old file on failure. */
    private void replaceFileSafely(File source, File target) throws IOException {
        File replacement = new File(target.getParentFile(), target.getName() + ".new");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        replacement.delete();
        backup.delete();
        copyFile(source, replacement);

        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            replacement.delete();
            throw new IOException("Unable to back up active rclone configuration");
        }
        if (!replacement.renameTo(target)) {
            boolean restored = !hadTarget || backup.renameTo(target);
            replacement.delete();
            if (!restored) {
                throw new IOException(
                        "Unable to activate imported rclone configuration or restore its backup");
            }
            throw new IOException("Unable to activate imported rclone configuration");
        }
        backup.delete();
    }


    /***
     * This function replaces the config by replacing rclone.conf.
     * First a rclone.conf-tmp is created, which is then verified to be working.
     * Then the rclone.conf is beeing replaced by the temp file.
     * @param uri Uri to the new rclone file.
     * @return True if rclone.conf has been replaced, false if not.
     * @throws IOException
     */
    public boolean copyConfigFile(Uri uri) throws IOException {
        File tempFile = new File(context.getFilesDir(), "rclone.conf-tmp");
        InputStream rawInput;
        try {
            rawInput = context.getContentResolver().openInputStream(uri);
        } catch (NullPointerException error) {
            throw new IOException(error);
        }
        if (rawInput == null) {
            throw new IOException("Unable to open rclone configuration");
        }

        try (InputStream input = rawInput;
             OutputStream output = new FileOutputStream(tempFile, false)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }

        try {
            if (!isValidConfig(tempFile.getAbsolutePath())) {
                return false;
            }
            replaceFileSafely(tempFile, new File(context.getFilesDir(), "rclone.conf"));
            return true;
        } finally {
            tempFile.delete();
        }
    }


    public boolean isValidConfig(String path) {
        String[] command = {rclone, "--ask-password=false", "--config", path, "listremotes"};
        try {
            Process process = getRuntimeProcess(command);
            process.waitFor();
            if (process.exitValue() != 0) {
                String stdOut;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                    stdOut = output.toString();
                }
                String stdErr = getCollectedErrorOutput(process);
                return !(stdOut.contains("could not parse line")
                        || stdErr.contains("could not parse line"));
            }
        } catch (IOException | InterruptedException e) {
            return false;
        }
        return true;
    }

    public void exportConfigFile(Uri uri) throws IOException {
        File configFile = new File(rcloneConf);
        if (!configFile.isFile()) {
            throw new IOException("rclone.conf does not exist");
        }

        OutputStream rawOutput = context.getContentResolver().openOutputStream(uri);
        if (rawOutput == null) {
            throw new IOException("Unable to open export destination");
        }

        try (OutputStream output = rawOutput;
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            writeZipTextEntry(zipOutput, "rcx.json", Exporter.create(context));
            writeZipTextEntry(zipOutput, "rcx.prefs", SharedPreferencesBackup.export(context));
            writeZipTextEntry(zipOutput, "quarkdav.json", QuarkDavRepository.exportBackup(context));
            writeZipTextEntry(zipOutput, "rclone.conf", readUtf8File(configFile));
            zipOutput.finish();
        } catch (Exception error) {
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Unable to export configuration package", error);
        }
    }

    private void writeZipTextEntry(ZipOutputStream output, String name, String content)
            throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }


    public boolean isCompatible() {
        if (isCompatible != null) {
            return isCompatible;
        }
        synchronized (Rclone.class) {
            if (isCompatible == null) {
                isCompatible = checkCompatibility();
            }
        }
        return isCompatible;
    }

    private boolean checkCompatibility() {
        String nativelibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File nativeRcloneBinary = new File(nativelibraryDir, "librclone.so");
        if (!nativeRcloneBinary.exists()) {
            return false;
        }
        if ("-1".equals(getRcloneVersion())) {
            return false;
        }
        return true;
    }

    /**
     * Prefixes local remotes with a base path on the primary external storage.
     * @param item
     * @param context
     * @return
     */
    public static String getLocalRemotePathPrefix(RemoteItem item, Context context) {
        if (item.isPathAlias()) {
            return "";
        }
        // lower version boundary check if legacy external storage = false
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            File extDir = context.getExternalFilesDir(null);
            if(null != extDir) {
                return extDir.getAbsolutePath();
            } else {
                File internalDir = context.getFilesDir();
                File fallbackLocal = new File(internalDir, "fallback-local");
                if (!fallbackLocal.exists() && !fallbackLocal.mkdir()) {
                    throw new IllegalStateException();
                }
                return fallbackLocal.getAbsolutePath();
            }
        } else {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        }
    }

    public ArrayList<Provider> getProviders() throws JSONException {
        return getProviders(false);
    }
    public ArrayList<Provider> getProviders(boolean silent) throws JSONException {

        JSONArray remotesJSON;
        int versionCode = BuildConfig.VERSION_CODE;
        File file = new File(context.getCacheDir(), "rclone.provider."+versionCode);

        if(!file.exists()) {
            String[] command = createCommand("config", "providers");
            StringBuilder output = new StringBuilder();
            Process process;

            try {
                process = getRuntimeProcess(command, getRcloneEnv());
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }

                process.waitFor();
                if (process.exitValue() != 0) {
                    if(!silent){
                        Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                    }
                    logErrorOutput(process);
                    return new ArrayList<>();
                }

                remotesJSON = new JSONArray(output.toString());
            } catch (IOException | InterruptedException | JSONException e) {
                FLog.e(TAG, "getRemotes: error retrieving remotes", e);
                return new ArrayList<>();
            }

            try {
                FileWriter fw = new FileWriter(file.getAbsoluteFile());
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(remotesJSON.toString(4));
                bw.close();
            } catch (IOException e) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                FLog.e(TAG, "Could not save providers to cache!", e);
                return new ArrayList<>();
            }
        } else {
            StringBuilder fileContent = new StringBuilder();
            try {
                FileInputStream inputstream = new FileInputStream(file);
                byte[] buffer = new byte[8128];
                int size;
                while ((size = inputstream.read(buffer)) != -1) {
                    fileContent.append(new String(buffer, 0, size));
                }
            } catch (IOException e) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                FLog.e(TAG, "Could not read cached providers, but the file exists! Please clear your app cache.", e);
                return new ArrayList<>();
            }

            remotesJSON = new JSONArray(fileContent.toString());
        }

        ArrayList<Provider> providerItems = new ArrayList<>();

        for (int i = 0; i < remotesJSON.length(); i++) {
            providerItems.add(Provider.Companion.newInstance(remotesJSON.getJSONObject(i)));
        }

        return providerItems;
    }

    public Provider getProvider(String name) throws JSONException {
        for (Provider provider : getProviders()) {
            if(provider.getName().equals(name)){
                return provider;
            }
        }
        return null;
    }

}
