package ca.pkay.rcloneexplorer.Fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.LogRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.util.SyncLog;

public class LogFragment extends Fragment {
    private static final String STATE_FOLLOW_LATEST = "follow_latest_logs";

    private View fragmentView;
    private RecyclerView recyclerView;
    private LinearLayoutManager layoutManager;
    private LogRecyclerViewAdapter adapter;
    private boolean receiverRegistered;
    private boolean followLatestLogs = true;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService logLoader = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loadScheduled = new AtomicBoolean(false);
    private final AtomicBoolean loadDirty = new AtomicBoolean(false);
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(); }
    };

    public LogFragment() { }
    public static LogFragment newInstance() { return new LogFragment(); }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            followLatestLogs = savedInstanceState.getBoolean(STATE_FOLLOW_LATEST, true);
        }
        FragmentActivity activity = getActivity();
        if (activity != null) activity.setTitle(R.string.logFragment);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        fragmentView = inflater.inflate(R.layout.fragment_logs, container, false);
        recyclerView = fragmentView.findViewById(R.id.log_list);
        layoutManager = new LinearLayoutManager(requireContext());
        recyclerView.setLayoutManager(layoutManager);
        // Live log updates should not animate/rebind the viewport into motion.
        recyclerView.setItemAnimator(null);
        adapter = new LogRecyclerViewAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int position = holder.getBindingAdapterPosition();
                String entryId = adapter.getEntryId(position);
                long timestamp = adapter.getTimestamp(position);
                if (timestamp > 0) SyncLog.deleteEntry(requireContext(), entryId, timestamp);
                reload();
            }
        }).attachToRecyclerView(recyclerView);
        return fragmentView;
    }

    @Override public void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(requireContext(), logReceiver, new IntentFilter(SyncLog.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        reload();
    }

    @Override public void onStop() {
        if (receiverRegistered) {
            requireContext().unregisterReceiver(logReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.log_fragment_menu, menu);
        updateFollowLatestMenuItem(menu.findItem(R.id.action_follow_latest_logs));
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_follow_latest_logs) {
            followLatestLogs = !followLatestLogs;
            updateFollowLatestMenuItem(item);
            if (followLatestLogs && recyclerView != null && layoutManager != null) {
                recyclerView.stopScroll();
                layoutManager.scrollToPositionWithOffset(0, 0);
            }
            return true;
        }
        if (item.getItemId() == R.id.action_clear_logs) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.action_clear_logs)
                    .setMessage(R.string.confirm_clear_logs)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (dialog, which) -> SyncLog.delete(requireContext()))
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        if (!isAdded() || adapter == null) return;
        loadDirty.set(true);
        if (!loadScheduled.compareAndSet(false, true)) return;
        mainHandler.postDelayed(() -> {
            if (!isAdded()) {
                loadScheduled.set(false);
                return;
            }
            Context app = requireContext().getApplicationContext();
            logLoader.execute(() -> {
                loadDirty.set(false);
                ArrayList<JSONObject> entries = SyncLog.getLog(app);
                mainHandler.post(() -> {
                    if (isAdded() && adapter != null && layoutManager != null && recyclerView != null) {
                        if (followLatestLogs) {
                            replaceAndScrollToLatest(entries);
                        } else {
                            preserveViewportWhileReplacing(entries);
                        }
                    }
                    loadScheduled.set(false);
                    if (loadDirty.get()) reload();
                });
            });
        }, 150L);
    }

    private void updateFollowLatestMenuItem(MenuItem item) {
        if (item == null) return;
        item.setChecked(followLatestLogs);
        item.setIcon(followLatestLogs ? R.drawable.ic_pin_filled : R.drawable.ic_pin);
    }

    private void replaceAndScrollToLatest(ArrayList<JSONObject> entries) {
        adapter.setList(entries);
        if (!entries.isEmpty()) {
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
    }

    private void preserveViewportWhileReplacing(ArrayList<JSONObject> entries) {
        int anchorPosition = layoutManager.findFirstVisibleItemPosition();
        String anchorId = "";
        long anchorTimestamp = -1L;
        int anchorOffset = 0;
        if (anchorPosition != RecyclerView.NO_POSITION) {
            anchorId = adapter.getEntryId(anchorPosition);
            anchorTimestamp = adapter.getTimestamp(anchorPosition);
            View anchorView = layoutManager.findViewByPosition(anchorPosition);
            if (anchorView != null) {
                anchorOffset = anchorView.getTop() - recyclerView.getPaddingTop();
            }
        }

        adapter.setList(entries);

        if (anchorPosition == RecyclerView.NO_POSITION) {
            return;
        }
        int replacementPosition = adapter.findPosition(anchorId, anchorTimestamp);
        if (replacementPosition != RecyclerView.NO_POSITION) {
            layoutManager.scrollToPositionWithOffset(replacementPosition, anchorOffset);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_FOLLOW_LATEST, followLatestLogs);
        super.onSaveInstanceState(outState);
    }

    @Override public void onDestroyView() {
        recyclerView = null;
        layoutManager = null;
        adapter = null;
        fragmentView = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        logLoader.shutdownNow();
        super.onDestroy();
    }
}
