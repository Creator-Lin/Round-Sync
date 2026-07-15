package ca.pkay.rcloneexplorer.Database.json;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.DARK;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.FOLLOW_SYSTEM;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.LIGHT;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import ca.pkay.rcloneexplorer.R;

/**
 * Versioned, type-safe backup for the app's default SharedPreferences file.
 *
 * The old implementation maintained a hand-written list of settings, which
 * inevitably drifted from the preferences used by the UI and background
 * services. Version 2 serializes every value in the preference file and keeps
 * a legacy importer for existing Round Sync backups.
 */
public final class SharedPreferencesBackup {

    private static final int SCHEMA_VERSION = 2;
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_PREFERENCES = "preferences";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";

    private SharedPreferencesBackup() { }

    public static String export(Context context) throws JSONException {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        JSONObject root = new JSONObject();
        JSONObject values = new JSONObject();
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);

        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            JSONObject encoded = encodeValue(entry.getValue());
            if (encoded != null) {
                values.put(entry.getKey(), encoded);
            }
        }
        root.put(KEY_PREFERENCES, values);
        return root.toString();
    }

    public static void validateJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.has(KEY_PREFERENCES)) {
            int schemaVersion = root.getInt(KEY_SCHEMA_VERSION);
            if (schemaVersion != SCHEMA_VERSION) {
                throw new JSONException("Unsupported preferences backup schema: " + schemaVersion);
            }
            decodeIntoEditor(root.getJSONObject(KEY_PREFERENCES), null);
        } else {
            validateLegacy(root);
        }
    }

    public static void importJson(String json, Context context) throws JSONException {
        JSONObject root = new JSONObject(json);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();

        if (root.has(KEY_PREFERENCES)) {
            int schemaVersion = root.getInt(KEY_SCHEMA_VERSION);
            if (schemaVersion != SCHEMA_VERSION) {
                throw new JSONException("Unsupported preferences backup schema: " + schemaVersion);
            }
            // A full backup represents the complete preference state. Clearing
            // first also removes settings retired by newer app versions.
            editor.clear();
            decodeIntoEditor(root.getJSONObject(KEY_PREFERENCES), editor);
        } else {
            importLegacy(root, context, editor);
        }

        if (!editor.commit()) {
            throw new JSONException("Unable to persist imported preferences");
        }
    }

    private static JSONObject encodeValue(Object value) throws JSONException {
        JSONObject encoded = new JSONObject();
        if (value instanceof Boolean) {
            encoded.put(KEY_TYPE, "boolean");
            encoded.put(KEY_VALUE, value);
        } else if (value instanceof Integer) {
            encoded.put(KEY_TYPE, "int");
            encoded.put(KEY_VALUE, value);
        } else if (value instanceof Long) {
            encoded.put(KEY_TYPE, "long");
            encoded.put(KEY_VALUE, value);
        } else if (value instanceof Float) {
            encoded.put(KEY_TYPE, "float");
            encoded.put(KEY_VALUE, ((Float) value).doubleValue());
        } else if (value instanceof String) {
            encoded.put(KEY_TYPE, "string");
            encoded.put(KEY_VALUE, value);
        } else if (value instanceof Set) {
            JSONArray array = new JSONArray();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) {
                    return null;
                }
                array.put(item);
            }
            encoded.put(KEY_TYPE, "string_set");
            encoded.put(KEY_VALUE, array);
        } else {
            return null;
        }
        return encoded;
    }

    private static void decodeIntoEditor(JSONObject values, SharedPreferences.Editor editor)
            throws JSONException {
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject encoded = values.getJSONObject(key);
            String type = encoded.getString(KEY_TYPE);
            switch (type) {
                case "boolean":
                    if (editor != null) editor.putBoolean(key, encoded.getBoolean(KEY_VALUE));
                    break;
                case "int":
                    if (editor != null) editor.putInt(key, encoded.getInt(KEY_VALUE));
                    break;
                case "long":
                    if (editor != null) editor.putLong(key, encoded.getLong(KEY_VALUE));
                    break;
                case "float":
                    if (editor != null) {
                        editor.putFloat(key, (float) encoded.getDouble(KEY_VALUE));
                    }
                    break;
                case "string":
                    if (editor != null) editor.putString(key, encoded.getString(KEY_VALUE));
                    break;
                case "string_set":
                    JSONArray array = encoded.getJSONArray(KEY_VALUE);
                    Set<String> set = new HashSet<>();
                    for (int index = 0; index < array.length(); index++) {
                        set.add(array.getString(index));
                    }
                    if (editor != null) editor.putStringSet(key, set);
                    break;
                default:
                    throw new JSONException("Unsupported preference type for " + key + ": " + type);
            }
        }
    }

    private static void validateLegacy(JSONObject root) throws JSONException {
        String[] booleans = {
                "isWifiOnly", "allowWhileIdle", "useProxy", "safEnabled",
                "refreshLaEnabled", "vcpEnabled", "vcpDeclareLocal", "vcpGrantAll",
                "isWrapFilenames", "appUpdates", "useLogs"
        };
        for (String key : booleans) {
            if (root.has(key)) root.getBoolean(key);
        }
        String[] strings = {"proxyProtocol", "proxyHost", "proxyUser", "proxyPassword"};
        for (String key : strings) {
            if (root.has(key)) root.getString(key);
        }
        if (root.has("proxyPort")) root.getInt("proxyPort");
        if (root.has("isDarkTheme")) parseLegacyTheme(root.get("isDarkTheme"));
    }

    private static int parseLegacyTheme(Object darkTheme) throws JSONException {
        if (darkTheme instanceof String) {
            try {
                return Integer.parseInt((String) darkTheme);
            } catch (NumberFormatException error) {
                throw new JSONException("Invalid legacy theme value");
            }
        }
        if (darkTheme instanceof Boolean) {
            return (Boolean) darkTheme ? DARK : LIGHT;
        }
        if (darkTheme instanceof Number) {
            return ((Number) darkTheme).intValue();
        }
        throw new JSONException("Invalid legacy theme value");
    }

    /** Imports backups produced by the pre-v2 hand-written preference exporter. */
    private static void importLegacy(
            JSONObject root,
            Context context,
            SharedPreferences.Editor editor) throws JSONException {
        putBooleanIfPresent(root, "isWifiOnly", editor,
                context.getString(R.string.pref_key_wifi_only_transfers));
        putBooleanIfPresent(root, "allowWhileIdle", editor,
                context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle));
        putBooleanIfPresent(root, "useProxy", editor,
                context.getString(R.string.pref_key_use_proxy));
        putStringIfPresent(root, "proxyProtocol", editor,
                context.getString(R.string.pref_key_proxy_protocol));
        putStringIfPresent(root, "proxyHost", editor,
                context.getString(R.string.pref_key_proxy_host));
        putIntIfPresent(root, "proxyPort", editor,
                context.getString(R.string.pref_key_proxy_port));
        putStringIfPresent(root, "proxyUser", editor,
                context.getString(R.string.pref_key_proxy_username));
        putStringIfPresent(root, "proxyPassword", editor,
                context.getString(R.string.pref_key_proxy_password));

        putBooleanIfPresent(root, "safEnabled", editor,
                context.getString(R.string.pref_key_enable_saf));
        putBooleanIfPresent(root, "refreshLaEnabled", editor,
                context.getString(R.string.pref_key_refresh_local_aliases));
        putBooleanIfPresent(root, "vcpEnabled", editor,
                context.getString(R.string.pref_key_enable_vcp));
        putBooleanIfPresent(root, "vcpDeclareLocal", editor,
                context.getString(R.string.pref_key_vcp_declare_local));
        putBooleanIfPresent(root, "vcpGrantAll", editor,
                context.getString(R.string.pref_key_vcp_grant_all));

        int theme = root.has("isDarkTheme")
                ? parseLegacyTheme(root.get("isDarkTheme"))
                : FOLLOW_SYSTEM;
        editor.putString(context.getString(R.string.pref_key_theme), String.valueOf(theme));
        putBooleanIfPresent(root, "isWrapFilenames", editor,
                context.getString(R.string.pref_key_wrap_filenames));
        putBooleanIfPresent(root, "appUpdates", editor,
                context.getString(R.string.pref_key_app_updates));
        putBooleanIfPresent(root, "useLogs", editor,
                context.getString(R.string.pref_key_logs));
    }

    private static void putBooleanIfPresent(
            JSONObject source, String jsonKey, SharedPreferences.Editor editor, String preferenceKey)
            throws JSONException {
        if (source.has(jsonKey)) editor.putBoolean(preferenceKey, source.getBoolean(jsonKey));
    }

    private static void putStringIfPresent(
            JSONObject source, String jsonKey, SharedPreferences.Editor editor, String preferenceKey)
            throws JSONException {
        if (source.has(jsonKey)) editor.putString(preferenceKey, source.getString(jsonKey));
    }

    private static void putIntIfPresent(
            JSONObject source, String jsonKey, SharedPreferences.Editor editor, String preferenceKey)
            throws JSONException {
        if (source.has(jsonKey)) editor.putInt(preferenceKey, source.getInt(jsonKey));
    }
}
