package ca.pkay.rcloneexplorer.RecyclerViewAdapters;


import android.content.Context;
import android.icu.text.DateFormat;
import android.os.Build;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;

import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.SyncLog;
import es.dmoral.toasty.Toasty;

public class LogRecyclerViewAdapter extends RecyclerView.Adapter<LogRecyclerViewAdapter.ViewHolder>{

    private ArrayList<JSONObject> entries;

    public LogRecyclerViewAdapter(ArrayList<JSONObject> entries) {
        this.entries = entries;
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_log_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final JSONObject selectedTrigger = entries.get(position);
        try {
            long timestamp = Long.parseLong(selectedTrigger.get(SyncLog.TIMESTAMP).toString());
            Date df = new Date(timestamp);
            String timeFormattedFull = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(df);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                timeFormattedFull = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(df);
            }
            String timeFormattedHuman = DateUtils.getRelativeTimeSpanString(timestamp).toString();

            String text = selectedTrigger.getString(SyncLog.TITLE);
            holder.logtitle.setText(text);
            holder.logtitle.setOnClickListener(v -> {
                Toasty.info(v.getContext(), text).show();
            });
            holder.logdetails.setText(selectedTrigger.getString(SyncLog.CONTENT));
            holder.logdate.setText(timeFormattedHuman);

            //required to make timeFormattedFull final, otherwise the lamda throws errors.
            //Can be removed when SimpleDateFormat in Line is dropped with the support for <21
            String timeFormattedFullFinal = timeFormattedFull;
            holder.log_item_frame.setOnClickListener(v -> {
                Toasty.info(v.getContext(), timeFormattedFullFinal).show();
            });


            Context c = holder.view.getContext();
            switch (selectedTrigger.getInt(SyncLog.TYPE)){
                case SyncLog.TYPE_ERROR:
                    holder.log_icon.setImageDrawable(AppCompatResources.getDrawable(c, R.drawable.ic_twotone_error_24));
                    break;
                case SyncLog.TYPE_INFO:
                    holder.log_icon.setImageDrawable(AppCompatResources.getDrawable(c, R.drawable.ic_twotone_info_24));
                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setList(ArrayList<JSONObject> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    public int findPosition(String entryId, long timestamp) {
        if (entries == null) return RecyclerView.NO_POSITION;
        for (int position = 0; position < entries.size(); position++) {
            JSONObject entry = entries.get(position);
            if (entryId != null && !entryId.isEmpty()) {
                if (entryId.equals(entry.optString(SyncLog.ENTRY_ID, ""))) return position;
            } else if (timestamp > 0L && entry.optLong(SyncLog.TIMESTAMP, -1L) == timestamp) {
                return position;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public long getItemId(int position) {
        if (entries == null || position < 0 || position >= entries.size()) {
            return RecyclerView.NO_ID;
        }
        JSONObject entry = entries.get(position);
        String entryId = entry.optString(SyncLog.ENTRY_ID, "");
        if (!entryId.isEmpty()) {
            try {
                UUID uuid = UUID.fromString(entryId);
                return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            } catch (IllegalArgumentException ignored) {
                return entryId.hashCode();
            }
        }
        return entry.optLong(SyncLog.TIMESTAMP, RecyclerView.NO_ID);
    }

    public String getEntryId(int position) {
        if (entries == null || position < 0 || position >= entries.size()) return "";
        return entries.get(position).optString(SyncLog.ENTRY_ID, "");
    }

    public long getTimestamp(int position) {
        if (entries == null || position < 0 || position >= entries.size()) return -1L;
        return entries.get(position).optLong(SyncLog.TIMESTAMP, -1L);
    }

    @Override
    public int getItemCount() {
        if (entries == null) {
            return 0;
        }
        return entries.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        final View view;

        final TextView logtitle;
        final TextView logdate;
        final TextView logdetails;
        final ConstraintLayout log_item_frame;
        final ImageView log_icon;

        ViewHolder(View itemView) {
            super(itemView);
            view = itemView;

            logtitle = view.findViewById(R.id.logtitle);
            logdate = view.findViewById(R.id.logdate);
            logdetails = view.findViewById(R.id.logDetails);
            log_item_frame = view.findViewById(R.id.log_item_frame);
            log_icon = view.findViewById(R.id.log_icon);
        }
    }
}
