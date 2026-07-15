package ca.pkay.rcloneexplorer.Fragments;

import static ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig.CONFIG_EDIT_CODE;
import static ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig.CONFIG_EDIT_TARGET;
import static ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig.QUARKDAV_EDIT_TARGET;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leinardi.android.speeddial.SpeedDialView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.pkay.rcloneexplorer.Activities.MainActivity;
import ca.pkay.rcloneexplorer.AppShortcutsHelper;
import ca.pkay.rcloneexplorer.Dialogs.RemotePropertiesDialog;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.RemotesRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRemote;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRepository;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRcloneIntegration;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavServiceActions;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavStatusStore;
import ca.pkay.rcloneexplorer.util.ActivityHelper;
import es.dmoral.toasty.Toasty;

public class RemotesFragment extends Fragment implements RemotesRecyclerViewAdapter.OnRemoteOptionsClick {

    private final int CONFIG_REQ_CODE = 171;
    private final int CONFIG_RECREATE_REQ_CODE = 156;

    private Rclone rclone;
    private RemotesRecyclerViewAdapter recyclerViewAdapter;
    private List<RemoteItem> remotes;
    private OnRemoteClickListener remoteClickListener;
    private AddRemoteToNavDrawer pinToDrawerListener;
    private Context context;
    private boolean statusReceiverRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshRemotes();
        }
    };

    public interface OnRemoteClickListener {
        void onRemoteClick(RemoteItem remote);
    }

    public interface AddRemoteToNavDrawer {
        void addRemoteToNavDrawer();
        void removeRemoteFromNavDrawer();
    }

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public RemotesFragment() {
    }

    @SuppressWarnings("unused")
    public static RemotesFragment newInstance() {
        return new RemotesFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getContext() == null) {
            return;
        }

        setHasOptionsMenu(true);

        rclone = new Rclone(getContext());
        remotes = filterRemotes();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view;
        remotes = filterRemotes();
        if (remotes.isEmpty()) {
            return getSpecialView(inflater, container, !rclone.isCompatible());
        }

        //ActivityHelper.applyTheme(this.context);
        view = inflater.inflate(R.layout.fragment_remotes_list, container, false);

        final Context context = view.getContext();
        RecyclerView recyclerView =  view.findViewById(R.id.remotes_list);
        // Avoid animating every remote row again when switching back from logs.
        recyclerView.setItemAnimator(null);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerViewAdapter = new RemotesRecyclerViewAdapter(remotes, this::handleRemoteClick, this);
        recyclerView.setAdapter(recyclerViewAdapter);

        SpeedDialView speedDialView = view.findViewById(R.id.fab_fragment_remote_list);
        speedDialView.setOnChangeListener(new SpeedDialView.OnChangeListener() {
            @Override
            public boolean onMainActionSelected() {
                Intent intent = new Intent(context, RemoteConfig.class);
                startActivityForResult(intent, CONFIG_REQ_CODE);
                return false;
            }

            @Override
            public void onToggleChanged(boolean isOpen) {

            }
        });

        return view;
    }

    // Prepares special views if rclone does not work or does not have a config file
    @NonNull
    private View getSpecialView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, boolean wrongAbi) {
        View view;
        view = inflater.inflate(R.layout.empty_state_config_file, container, false);
        if (wrongAbi) {
            SpeedDialView speedDialView = view.findViewById(R.id.fab_empty_config);
            speedDialView.setVisibility(View.VISIBLE);
            speedDialView.setOnChangeListener(new SpeedDialView.OnChangeListener() {
                @Override
                public boolean onMainActionSelected() {
                    startActivityForResult(new Intent(context, RemoteConfig.class), CONFIG_RECREATE_REQ_CODE);
                    return false;
                }
                @Override public void onToggleChanged(boolean isOpen) { }
            });
            Button btn = view.findViewById(R.id.empty_state_btn);
            btn.setVisibility(View.GONE);
            TextView textView = view.findViewById(R.id.empty_state_message);
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis.length >= 2 && (abis[0].equals("arm64-v8a") || abis[0].equals("x86") || abis[0].equals("x86_64"))) {
                for (int i = 1; i < abis.length; i++) {
                    if (abis[i].equals("armeabi-v7a") || abis[i].equals("armeabi")) {
                        textView.setText(R.string.abi_not_supported_arm_downgrade);
                        return view;
                    }
                }
            }
            textView.setText(R.string.abi_not_supported);
            return view;
        }
        view.findViewById(R.id.empty_state_btn).setOnClickListener(v -> {
            Uri externalConfig;
            if(null != (externalConfig = rclone.searchExternalConfig())){
                if (getActivity() != null) {
                    ((MainActivity) getActivity()).askUseExternalConfig(externalConfig);
                }
            } else {
                if (getActivity() != null) {
                    ((MainActivity) getActivity()).importConfigFile();
                }
            }
        });

        SpeedDialView speedDialView = view.findViewById(R.id.fab_empty_config);
        speedDialView.setOnChangeListener(new SpeedDialView.OnChangeListener() {
            @Override
            public boolean onMainActionSelected() {
                Intent intent = new Intent(context, RemoteConfig.class);
                startActivityForResult(intent, CONFIG_RECREATE_REQ_CODE);
                return false;
            }

            @Override
            public void onToggleChanged(boolean isOpen) {

            }
        });
        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.remote_fragment_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_hidden_remotes:
                showHiddenRemotesDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);

        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CONFIG_REQ_CODE || requestCode == CONFIG_RECREATE_REQ_CODE || requestCode == CONFIG_EDIT_CODE) {
            refreshFragment();
        }
    }

    private void refreshFragment() {
        if (getFragmentManager() == null || isStateSaved()) {
            return;
        }

        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.detach(this);
        fragmentTransaction.attach(this);
        fragmentTransaction.commit();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
        if (context instanceof OnRemoteClickListener) {
            remoteClickListener = (OnRemoteClickListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement OnRemoteClickListener");
        }
        if (context instanceof AddRemoteToNavDrawer) {
            pinToDrawerListener = (AddRemoteToNavDrawer) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement AddRemoteToNavDrawer");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (context != null && !statusReceiverRegistered) {
            ContextCompat.registerReceiver(
                    context,
                    statusReceiver,
                    new IntentFilter(QuarkDavStatusStore.ACTION_STATUS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            statusReceiverRegistered = true;
            refreshRemotes();
        }
    }

    @Override
    public void onStop() {
        if (context != null && statusReceiverRegistered) {
            context.unregisterReceiver(statusReceiver);
            statusReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        context = null;
        remoteClickListener = null;
        pinToDrawerListener = null;
    }

    private void handleRemoteClick(RemoteItem remoteItem) {
        if (remoteItem.isQuarkDav()) {
            Intent intent = new Intent(context, RemoteConfig.class);
            intent.putExtra(QUARKDAV_EDIT_TARGET, remoteItem.getQuarkDavId());
            startActivityForResult(intent, CONFIG_EDIT_CODE);
        } else if (remoteClickListener != null) {
            remoteClickListener.onRemoteClick(remoteItem);
        }
    }

    @Override
    public void onRemoteOptionsClicked(View view, RemoteItem remoteItem) {
        showRemoteMenu(view, remoteItem);
    }

    private void showRemoteMenu(View view, final RemoteItem remoteItem) {
        PopupMenu popupMenu = new PopupMenu(context, view);
        popupMenu.getMenuInflater().inflate(R.menu.remote_options, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemID = item.getItemId();

            if (itemID == R.id.action_quarkdav_toggle) {
                toggleQuarkDav(remoteItem);
            } else if (itemID == R.id.action_quarkdav_copy_address) {
                copyQuarkDavAddress(remoteItem);
            } else if (itemID == R.id.action_quarkdav_create_rclone) {
                createRcloneWebDav(remoteItem);
            } else if(itemID == R.id.action_remote_properties) {
                showRemotePropertiesDialog(remoteItem);
            } else if (itemID == R.id.action_edit_remote) {
                Intent intent = new Intent(context, RemoteConfig.class);
                if (remoteItem.isQuarkDav()) intent.putExtra(QUARKDAV_EDIT_TARGET, remoteItem.getQuarkDavId());
                else intent.putExtra(CONFIG_EDIT_TARGET, remoteItem.getName());
                startActivityForResult(intent, CONFIG_EDIT_CODE);
            } else if (itemID == R.id.action_delete) {
                if (remoteItem.isQuarkDav()) deleteQuarkDav(remoteItem); else deleteRemote(remoteItem);
            } else if (itemID == R.id.action_remote_rename) {
                if (remoteItem.isQuarkDav()) renameQuarkDav(remoteItem); else renameRemote(remoteItem);
            } else if (itemID == R.id.action_pin) {
                if (remoteItem.isPinned()) {
                    unPinRemote(remoteItem);
                } else {
                    pinRemote(remoteItem);
                }
            } else if (itemID == R.id.action_favorite) {
                if (remoteItem.isDrawerPinned()) {
                    unpinFromDrawer(remoteItem);
                } else {
                    pinToDrawer(remoteItem);
                }
            } else if (itemID == R.id.action_add_to_home_screen) {
                AppShortcutsHelper.addRemoteToHomeScreen(context, remoteItem);
            } else {
                return false;
            }

            return true;
        });
        configureQuarkDavMenu(popupMenu, remoteItem);
        popupMenu.show();

        MenuItem pinAction = popupMenu.getMenu().findItem(R.id.action_pin);
        if (remoteItem.isPinned()) {
            pinAction.setTitle(R.string.unpin_from_the_top);
        } else {
            pinAction.setTitle(R.string.pin_to_the_top);
        }
        if (!AppShortcutsHelper.isRequestPinShortcutSupported(context)) {
            MenuItem addToHomeScreenAction = popupMenu.getMenu().findItem(R.id.action_add_to_home_screen);
            addToHomeScreenAction.setVisible(false);
        }

        MenuItem favoriteAction = popupMenu.getMenu().findItem(R.id.action_favorite);
        if (remoteItem.isDrawerPinned()) {
            favoriteAction.setTitle(R.string.unpin_from_drawer);
        } else {
            favoriteAction.setTitle(R.string.pin_to_drawer);
        }
    }

    private void configureQuarkDavMenu(PopupMenu popupMenu, RemoteItem remoteItem) {
        boolean isQuark = remoteItem.isQuarkDav();
        popupMenu.getMenu().findItem(R.id.action_quarkdav_toggle).setVisible(isQuark);
        popupMenu.getMenu().findItem(R.id.action_quarkdav_copy_address).setVisible(isQuark);
        popupMenu.getMenu().findItem(R.id.action_quarkdav_create_rclone).setVisible(isQuark && rclone.isCompatible());
        if (!isQuark) return;

        QuarkDavRemote remote = QuarkDavRepository.INSTANCE.get(context, remoteItem.getQuarkDavId());
        popupMenu.getMenu().findItem(R.id.action_quarkdav_toggle)
                .setTitle(remote != null && remote.getEnabled() ? R.string.quarkdav_action_stop : R.string.quarkdav_action_start);
        popupMenu.getMenu().findItem(R.id.action_remote_properties).setVisible(false);
        popupMenu.getMenu().findItem(R.id.action_favorite).setVisible(false);
        popupMenu.getMenu().findItem(R.id.action_add_to_home_screen).setVisible(false);
    }

    private void refreshRemotes() {
        remotes = filterRemotes();
        if (null != recyclerViewAdapter) {
            recyclerViewAdapter.newData(remotes);
        }
    }

    private List<RemoteItem> filterRemotes() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> hiddenRemotes = sharedPreferences.getStringSet(getString(R.string.shared_preferences_hidden_remotes), new HashSet<>());
        remotes = new ArrayList<>();
        if (rclone.isCompatible()) remotes.addAll(rclone.getRemotes());
        for (QuarkDavRemote remote : QuarkDavRepository.INSTANCE.list(context)) {
            remotes.add(remote.toRemoteItem(context));
        }
        if (hiddenRemotes != null && !hiddenRemotes.isEmpty()) {
            ArrayList<RemoteItem> toBeHidden = new ArrayList<>();
            for (RemoteItem remoteItem : remotes) {
                if (hiddenRemotes.contains(remoteItem.getName())) {
                    toBeHidden.add(remoteItem);
                }
            }
            remotes.removeAll(toBeHidden);
        }
        RemoteItem.prepareDisplay(context, remotes);
        Collections.sort(remotes);
        return remotes;
    }

    private void showRemotePropertiesDialog(RemoteItem remoteItem) {
        RemotePropertiesDialog dialog = RemotePropertiesDialog.newInstance(remoteItem, ActivityHelper.isDarkTheme(this.getActivity()));
        if (getFragmentManager() != null) {
            dialog.show(getChildFragmentManager(), "remote properties");
        }
    }

    private void showHiddenRemotesDialog() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> hiddenRemotes = sharedPreferences.getStringSet(getString(R.string.shared_preferences_hidden_remotes), new HashSet<>());

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        builder.setTitle(R.string.select_remotes_to_hide);
        final ArrayList<RemoteItem> remotes = new ArrayList<>();
        if (rclone.isCompatible()) remotes.addAll(rclone.getRemotes());
        for (QuarkDavRemote remote : QuarkDavRepository.INSTANCE.list(context)) remotes.add(remote.toRemoteItem(context));
        RemoteItem.prepareDisplay(context, remotes);
        Collections.sort(remotes);
        final CharSequence[] remoteDisplayNames = new CharSequence[remotes.size()];
        final String[] remoteIds = new String[remotes.size()];
        for (int i = 0; i < remoteDisplayNames.length; i++) {
            remoteDisplayNames[i] = remotes.get(i).getDisplayName();
            remoteIds[i] = remotes.get(i).getName();
        }

        final ArrayList<String> userSelected = new ArrayList<>();
        boolean[] checkedItems = new boolean[remoteDisplayNames.length];

        for (int i = 0; i < remoteIds.length; i++) {
            String remoteId = remoteIds[i];
            if (hiddenRemotes.contains(remoteId)) {
                userSelected.add(remoteId);
                checkedItems[i] = true;
            }
        }

        builder.setMultiChoiceItems(remoteDisplayNames, checkedItems, (dialog, which, isChecked) -> {
            if (isChecked) {
                userSelected.add(remoteIds[which]);
            } else {
                userSelected.remove(remoteIds[which]);
            }
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.select, (dialog, which) -> {
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
            editor.remove(getString(R.string.shared_preferences_hidden_remotes));

            if (userSelected.isEmpty()) {
                editor.apply();
                refreshRemotes();
                return;
            }

            Set<String> updatedHiddenRemotesIds = new HashSet<>(userSelected);

            editor.putStringSet(getString(R.string.shared_preferences_hidden_remotes), updatedHiddenRemotesIds);
            editor.apply();
            refreshRemotes();
        });

        builder.show();
    }

    private void pinRemote(RemoteItem remoteItem) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        Set<String> stringSet = sharedPreferences.getStringSet(getString(R.string.shared_preferences_pinned_remotes), new HashSet<>());
        Set<String> pinnedRemotes = new HashSet<>(stringSet); // bug in android means that we have to create a copy
        pinnedRemotes.add(remoteItem.getName());
        remoteItem.pin(true);

        editor.putStringSet(getString(R.string.shared_preferences_pinned_remotes), pinnedRemotes);
        editor.apply();

        int from = remotes.indexOf(remoteItem);
        Collections.sort(remotes);
        int to = remotes.indexOf(remoteItem);
        recyclerViewAdapter.moveDataItem(remotes, from, to);
    }

    private void unPinRemote(RemoteItem remoteItem) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        Set<String> stringSet = sharedPreferences.getStringSet(getString(R.string.shared_preferences_pinned_remotes), new HashSet<>());
        Set<String> pinnedRemotes = new HashSet<>(stringSet);
        if (pinnedRemotes.contains(remoteItem.getName())) {
            pinnedRemotes.remove(remoteItem.getName());
        }
        remoteItem.pin(false);

        editor.putStringSet(getString(R.string.shared_preferences_pinned_remotes), pinnedRemotes);
        editor.apply();

        int from = remotes.indexOf(remoteItem);
        Collections.sort(remotes);
        int to = remotes.indexOf(remoteItem);
        recyclerViewAdapter.moveDataItem(remotes, from, to);
    }

    private void pinToDrawer(RemoteItem remoteItem) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        Set<String> stringSet = sharedPreferences.getStringSet(getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());
        Set<String> pinnedRemotes = new HashSet<>(stringSet); // bug in android means that we have to create a copy
        pinnedRemotes.add(remoteItem.getName());
        remoteItem.setDrawerPinned(true);

        editor.putStringSet(getString(R.string.shared_preferences_drawer_pinned_remotes), pinnedRemotes);
        editor.apply();

        pinToDrawerListener.addRemoteToNavDrawer();
    }

    private void unpinFromDrawer(RemoteItem remoteItem) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        Set<String> stringSet = sharedPreferences.getStringSet(getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());
        Set<String> pinnedRemotes = new HashSet<>(stringSet);
        if (pinnedRemotes.contains(remoteItem.getName())) {
            pinnedRemotes.remove(remoteItem.getName());
        }
        remoteItem.setDrawerPinned(false);

        editor.putStringSet(getString(R.string.shared_preferences_drawer_pinned_remotes), pinnedRemotes);
        editor.apply();

        pinToDrawerListener.removeRemoteFromNavDrawer();
    }

    private void toggleQuarkDav(RemoteItem item) {
        QuarkDavRemote remote = QuarkDavRepository.INSTANCE.get(context, item.getQuarkDavId());
        if (remote == null) return;
        if (remote.getEnabled()) QuarkDavServiceActions.INSTANCE.stopRemote(context, remote.getId());
        else QuarkDavServiceActions.INSTANCE.startRemote(context, remote.getId());
        refreshRemotes();
    }

    private void copyQuarkDavAddress(RemoteItem item) {
        QuarkDavRemote remote = QuarkDavRepository.INSTANCE.get(context, item.getQuarkDavId());
        if (remote == null) return;
        String address = remote.displayWebDavUrl(context);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("WebDAV", address));
        Toasty.success(context, getString(R.string.quarkdav_address_copied), Toast.LENGTH_SHORT, true).show();
    }

    private void createRcloneWebDav(RemoteItem item) {
        QuarkDavRemote remote = QuarkDavRepository.INSTANCE.get(context, item.getQuarkDavId());
        if (remote == null) return;

        final String suggestedName = QuarkDavRcloneIntegration.buildRcloneName(remote.getName());
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null, false);
        TextInputLayout inputLayout = dialogView.findViewById(R.id.dialog_input_layout);
        TextInputEditText input = dialogView.findViewById(R.id.dialog_input);
        inputLayout.setHint(getString(R.string.quarkdav_rclone_name_label));
        inputLayout.setExpandedHintEnabled(false);
        inputLayout.setPlaceholderText(suggestedName);
        inputLayout.setHelperText(getString(R.string.quarkdav_rclone_name_helper, suggestedName));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog)
                .setTitle(R.string.quarkdav_rclone_name_title)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                // Install the positive click listener after showing the dialog so an invalid
                // name can keep the dialog open and display an inline error.
                .setPositiveButton(R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String requestedName = input.getText() == null
                            ? ""
                            : input.getText().toString();
                    String resolvedName = QuarkDavRcloneIntegration.resolveRcloneName(
                            remote.getName(), requestedName);
                    if (!QuarkDavRcloneIntegration.isValidRcloneName(resolvedName)) {
                        inputLayout.setError(getString(R.string.quarkdav_rclone_invalid_name));
                        return;
                    }
                    inputLayout.setError(null);
                    dialog.dismiss();
                    createRcloneWebDav(item, resolvedName);
                }));
        dialog.show();
    }

    private void createRcloneWebDav(RemoteItem item, String rcloneName) {
        final String id = item.getQuarkDavId();
        final Context appContext = context.getApplicationContext();
        new AsyncTask<Void, Void, String>() {
            private Throwable error;
            @Override protected String doInBackground(Void... values) {
                try { return QuarkDavRcloneIntegration.createOrUpdate(appContext, id, rcloneName); }
                catch (Throwable t) { error = t; return null; }
            }
            @Override protected void onPostExecute(String name) {
                Context uiContext = getContext();
                if (uiContext == null) return;
                if (name != null) {
                    Toasty.success(uiContext, getString(R.string.quarkdav_rclone_remote_created) + ": " + name, Toast.LENGTH_LONG, true).show();
                    // The remote list already has a RecyclerView because this action is
                    // launched from an existing QuarkDav item. Refresh its backing data
                    // directly. A detach/attach pair in the same FragmentTransaction may
                    // be optimized into a no-op, leaving the newly-created rclone remote
                    // invisible until the user refreshes the screen manually.
                    refreshRemotes();
                } else {
                    Toasty.error(uiContext, getString(R.string.quarkdav_rclone_remote_failed, error == null ? "unknown" : error.getMessage()), Toast.LENGTH_LONG, true).show();
                }
            }
        }.execute();
    }

    private void renameQuarkDav(final RemoteItem item) {
        QuarkDavRemote remote = QuarkDavRepository.INSTANCE.get(context, item.getQuarkDavId());
        if (remote == null) return;
        EditText input = new EditText(context);
        input.setText(remote.getName());
        new AlertDialog.Builder(context)
                .setTitle(R.string.rename_remote)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.select, (dialog, which) -> {
                    try {
                        QuarkDavRepository.INSTANCE.rename(context, remote.getId(), input.getText().toString());
                        refreshRemotes();
                    } catch (Throwable t) {
                        Toasty.error(context, getString(R.string.quarkdav_error_save, t.getMessage()), Toast.LENGTH_LONG, true).show();
                    }
                }).show();
    }

    private void deleteQuarkDav(final RemoteItem item) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.delete_remote_title)
                .setMessage(R.string.quarkdav_confirm_delete)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    QuarkDavServiceActions.INSTANCE.stopRemote(context, item.getQuarkDavId());
                    QuarkDavRepository.INSTANCE.delete(context, item.getQuarkDavId());
                    Toasty.success(context, getString(R.string.quarkdav_deleted), Toast.LENGTH_SHORT, true).show();
                    refreshFragment();
                }).show();
    }

    private void renameRemote(final RemoteItem remoteItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        final EditText remoteNameEdit = new EditText(context);
        String initialText = remoteItem.getDisplayName();
        remoteNameEdit.setText(initialText);
        builder.setView(remoteNameEdit);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.select, (dialog, which) -> {
            String displayName = remoteNameEdit.getText().toString();
            Set<String> renamedRemotes = pref.getStringSet(getString(R.string.pref_key_renamed_remotes), new HashSet<>());
            renamedRemotes.add(remoteItem.getName());
            pref.edit()
                    .putString(getString(R.string.pref_key_renamed_remote_prefix, remoteItem.getName()), displayName)
                    .putStringSet(getString(R.string.pref_key_renamed_remotes), renamedRemotes)
                .apply();
            remoteItem.setDisplayName(displayName);
            refreshRemotes();
        });
        builder.setTitle(R.string.rename_remote);
        builder.show();
    }

    private void deleteRemote(final RemoteItem remoteItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.delete_remote_title);
        builder.setMessage(remoteItem.getDisplayName());
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.delete, (dialog, which) -> new DeleteRemote(context, remoteItem).execute());
        builder.show();
    }

    @SuppressLint("StaticFieldLeak")
    private class DeleteRemote extends AsyncTask<Void, Void, Void> {

        private final RemoteItem remoteItem;
        private final Context context;

        public DeleteRemote(Context context, RemoteItem remoteItem) {
            this.remoteItem = remoteItem;
            this.context = context.getApplicationContext();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            rclone.deleteRemote(remoteItem.getName());
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = sharedPreferences.edit();

            Set<String> pinnedRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_pinned_remotes), new HashSet<>());
            if (pinnedRemotes.contains(remoteItem.getName())) {
                pinnedRemotes.remove(remoteItem.getName());
                editor.putStringSet(context.getString(R.string.shared_preferences_pinned_remotes), new HashSet<>(pinnedRemotes));
                editor.apply();
            }

            Set<String> hiddenRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_hidden_remotes), new HashSet<>());
            if (hiddenRemotes.contains(remoteItem.getName())) {
                hiddenRemotes.remove(remoteItem.getName());
                editor.putStringSet(context.getString(R.string.shared_preferences_hidden_remotes), new HashSet<>(hiddenRemotes));
                editor.apply();
            }

            AppShortcutsHelper.removeAppShortcut(context, remoteItem.getName());

            Set<String> drawerPinnedRemote = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());
            if (drawerPinnedRemote.contains(remoteItem.getName())) {
                drawerPinnedRemote.remove(remoteItem.getName());
                editor.putStringSet(context.getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>(pinnedRemotes));
                editor.apply();
                pinToDrawerListener.removeRemoteFromNavDrawer();
            }
            
            recyclerViewAdapter.removeItem(remoteItem);

            if (rclone.getRemotes().isEmpty()) {
                refreshFragment();
            }
        }
    }
}
