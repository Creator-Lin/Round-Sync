package ca.pkay.rcloneexplorer.Database.json;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import ca.pkay.rcloneexplorer.Database.DatabaseHandler;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.Items.Trigger;

/** Imports the task/trigger portion of a Round Sync configuration package. */
public final class Importer {

    private Importer() { }

    public static void validateJson(String json) throws JSONException {
        ParsedBackup parsed = parse(json);
        Set<Long> taskIds = new HashSet<>();
        for (Task task : parsed.tasks) {
            if (task.getId() <= 0) {
                throw new JSONException("Invalid task ID: " + task.getId());
            }
            if (!taskIds.add(task.getId())) {
                throw new JSONException("Duplicate task ID: " + task.getId());
            }
        }
        Set<Long> triggerIds = new HashSet<>();
        for (Trigger trigger : parsed.triggers) {
            if (trigger.getId() <= 0) {
                throw new JSONException("Invalid trigger ID: " + trigger.getId());
            }
            if (!triggerIds.add(trigger.getId())) {
                throw new JSONException("Duplicate trigger ID: " + trigger.getId());
            }
            if (trigger.getTriggerTarget() > 0 && !taskIds.contains(trigger.getTriggerTarget())) {
                throw new JSONException("Trigger references a missing task: " + trigger.getTriggerTarget());
            }
        }
    }

    public static void importJson(String json, Context context) throws JSONException {
        ParsedBackup parsed = parse(json);
        validateJson(json);

        DatabaseHandler dbHandler = new DatabaseHandler(context);
        try {
            dbHandler.replaceAll(parsed.tasks, parsed.triggers);
        } catch (RuntimeException error) {
            throw new JSONException("Unable to restore task database: " + error.getMessage());
        }
    }

    public static ArrayList<Trigger> createTriggerlist(String content) throws JSONException {
        return parse(content).triggers;
    }

    public static ArrayList<Task> createTasklist(String content) throws JSONException {
        return parse(content).tasks;
    }

    private static ParsedBackup parse(String content) throws JSONException {
        ArrayList<Trigger> triggers = new ArrayList<>();
        ArrayList<Task> tasks = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        if (reader.has("schemaVersion") && reader.getInt("schemaVersion") != 1) {
            throw new JSONException("Unsupported task backup schema: "
                    + reader.getInt("schemaVersion"));
        }

        JSONArray triggerArray = reader.getJSONArray("trigger");
        for (int i = 0; i < triggerArray.length(); i++) {
            triggers.add(Trigger.Companion.fromString(triggerArray.getJSONObject(i).toString()));
        }

        JSONArray taskArray = reader.getJSONArray("tasks");
        for (int i = 0; i < taskArray.length(); i++) {
            tasks.add(Task.Companion.fromString(taskArray.getJSONObject(i).toString()));
        }
        return new ParsedBackup(triggers, tasks);
    }

    private static final class ParsedBackup {
        private final ArrayList<Trigger> triggers;
        private final ArrayList<Task> tasks;

        private ParsedBackup(ArrayList<Trigger> triggers, ArrayList<Task> tasks) {
            this.triggers = triggers;
            this.tasks = tasks;
        }
    }
}
