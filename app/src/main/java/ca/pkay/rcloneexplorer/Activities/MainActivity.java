package ca.pkay.rcloneexplorer.Activities;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivityForResult;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ca.pkay.rcloneexplorer.AppShortcutsHelper;
import ca.pkay.rcloneexplorer.BuildConfig;
import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.Importer;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import ca.pkay.rcloneexplorer.Dialogs.Dialogs;
import ca.pkay.rcloneexplorer.Dialogs.InputDialog;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Fragments.FileExplorerFragment;
import ca.pkay.rcloneexplorer.Fragments.LogFragment;
import ca.pkay.rcloneexplorer.Fragments.PermissionFragment;
import ca.pkay.rcloneexplorer.Fragments.RemotesFragment;
import ca.pkay.rcloneexplorer.Fragments.TasksFragment;
import ca.pkay.rcloneexplorer.Fragments.TriggerFragment;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfigHelper;
import ca.pkay.rcloneexplorer.RuntimeConfiguration;
import ca.pkay.rcloneexplorer.Services.StreamingService;
import ca.pkay.rcloneexplorer.Services.TriggerService;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRepository;
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavServiceActions;
import ca.pkay.rcloneexplorer.util.ActivityHelper;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.PermissionManager;
import ca.pkay.rcloneexplorer.util.SharedPreferencesUtil;
import de.felixnuesse.extract.updates.UpdateChecker;
import es.dmoral.toasty.Toasty;
import java9.util.stream.Stream;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        RemotesFragment.OnRemoteClickListener,
        RemotesFragment.AddRemoteToNavDrawer,
        InputDialog.OnPositive {

    private static final String TAG = "MainActivity";
    public static final String MAIN_ACTIVITY_START_LOG = "MAIN_ACTIVITY_START_LOG";
    public static final String MAIN_ACTIVITY_START_IMPORT = "MAIN_ACTIVITY_START_IMPORT";
    public static final String MAIN_ACTIVITY_START_EXPORT = "MAIN_ACTIVITY_START_EXPORT";
    private static final int READ_REQUEST_CODE = 42; // code when opening rclone config file
    private static final int REQUEST_PERMISSION_CODE = 62; // code when requesting permissions
    private static final int REQUEST_PERMISSION_CODE_POST_NOTIFICATIONS = 63;
    private static final int SETTINGS_CODE = 71; // code when coming back from settings
    private static final int WRITE_REQUEST_CODE = 81; // code when exporting config
    private static final String FILE_EXPLORER_FRAGMENT_TAG =
            "ca.pkay.rcexplorer.MAIN_ACTIVITY_FILE_EXPLORER_TAG";
    private static final String LOG_FRAGMENT_TAG =
            "ca.pkay.rcexplorer.MAIN_ACTIVITY_LOG_TAG";
    private static final String LOG_BACK_STACK_NAME =
            "ca.pkay.rcexplorer.MAIN_ACTIVITY_LOG_OVER_FILE_EXPLORER";
    private static final String STATE_NAVIGATION_ITEM_BEFORE_LOG =
            "ca.pkay.rcexplorer.MAIN_ACTIVITY_NAVIGATION_ITEM_BEFORE_LOG";
    private static final String STATE_RESTORE_NAVIGATION_AFTER_LOG =
            "ca.pkay.rcexplorer.MAIN_ACTIVITY_RESTORE_NAVIGATION_AFTER_LOG";
    private NavigationView navigationView;
    private DrawerLayout drawer;
    private Rclone rclone;
    private Fragment fragment;
    private Context context;
    private HashMap<Integer, RemoteItem> drawerPinnedRemoteIds;
    private int availableDrawerPinnedRemoteId;
    private boolean restoreImportedShortcutSelectionAfterDecrypt;
    private int navigationItemBeforeLog = R.id.nav_remotes;
    private boolean restoreNavigationItemAfterLog;
    private AlertDialog overwriteConfigurationDialog;
    private AlertDialog externalConfigurationDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityHelper.applyTheme(this);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean allPermissionsGranted = (new PermissionManager(this)).hasAllRequiredPermissions();
        boolean completedIntroOnce = OnboardingActivity.Companion.completedIntro(this);
        if(!allPermissionsGranted || !completedIntroOnce) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
        }


        context = this;
        drawerPinnedRemoteIds = new HashMap<>();
        availableDrawerPinnedRemoteId = 2;
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionbar = getSupportActionBar();
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true);
            actionbar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }

        drawer = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        if (savedInstanceState != null) {
            navigationItemBeforeLog = savedInstanceState.getInt(
                    STATE_NAVIGATION_ITEM_BEFORE_LOG, R.id.nav_remotes);
            restoreNavigationItemAfterLog = savedInstanceState.getBoolean(
                    STATE_RESTORE_NAVIGATION_AFTER_LOG, false);
        }
        getSupportFragmentManager().addOnBackStackChangedListener(
                this::syncCurrentFragmentAfterNavigation);

        rclone = new Rclone(this);
        // Recover enabled cookie-backed WebDAV instances whenever the user opens the app.
        QuarkDavServiceActions.INSTANCE.reconcileIfEnabled(this);

        findViewById(R.id.locked_config_btn).setOnClickListener(v -> askForConfigPassword());

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();

        int lastVersionCode = sharedPreferences.getInt(getString(R.string.pref_key_version_code), -1);
        String lastVersionName = sharedPreferences.getString(getString(R.string.pref_key_version_name), "");
        int currentVersionCode = BuildConfig.VERSION_CODE / 10; // drop ABI flag digit
        String currentVersionName = BuildConfig.VERSION_NAME;

        if (lastVersionCode < currentVersionCode || !lastVersionName.equals(currentVersionName)) {
            if (lastVersionCode == 9) {
                AppShortcutsHelper.removeAllAppShortcuts(this);
                AppShortcutsHelper.populateAppShortcuts(this, rclone.getRemotes());
            }

            startRemotesFragment();

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(getString(R.string.pref_key_version_code), currentVersionCode);
            editor.putString(getString(R.string.pref_key_version_name), currentVersionName);
            editor.apply();
        } else if (rclone.isConfigEncrypted()) {
            askForConfigPassword();
        } else if (savedInstanceState != null) {
            // FragmentManager already restored both the visible fragment and its back stack.
            // Replacing the restored fragment here would discard a Log -> FileExplorer return
            // path and could also duplicate fragments after a configuration change.
            fragment = getCurrentFragment();
            if (fragment == null) {
                startRemotesFragment();
            }
        } else if (bundle != null && bundle.containsKey(AppShortcutsHelper.APP_SHORTCUT_REMOTE_NAME)) {
            String remoteName = bundle.getString(AppShortcutsHelper.APP_SHORTCUT_REMOTE_NAME);
            RemoteItem remoteItem = rclone.getRemoteItemFromName(remoteName);
            if (remoteItem != null) {
                AppShortcutsHelper.reportAppShortcutUsage(this, remoteItem.getName());
                startRemote(remoteItem, false);
            } else {
                Toasty.error(this, getString(R.string.remote_not_found), Toast.LENGTH_SHORT, true).show();
                finish();
            }
        } else {
            startRemotesFragment();
        }

        // Custom actions are commands, not persistent Activity state. Window-mode changes
        // can recreate this Activity with the same launch Intent, so only consume them on
        // a genuinely new launch. New commands delivered to an existing instance are
        // handled in onNewIntent().
        if (savedInstanceState == null) {
            handleOneShotMainAction(intent);
        }

        findViewById(R.id.navAbout).setOnClickListener(v -> {
            Intent aboutIntent = new Intent(this, AboutActivity.class);
            startActivity(aboutIntent);
        });

        findViewById(R.id.navSettings).setOnClickListener(v -> {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            tryStartActivityForResult(this, settingsIntent, SETTINGS_CODE);
        });

        pinRemotesToDrawer();
        syncCurrentFragmentAfterNavigation();
        updatePermissionFragmentVisibility();
        TriggerService triggerService = new TriggerService(context);
        triggerService.queueTrigger();

        (new UpdateChecker(this)).schedule();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionFragmentVisibility();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOneShotMainAction(intent);
    }

    private void handleOneShotMainAction(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        boolean handled = true;
        if (MAIN_ACTIVITY_START_LOG.equals(action)) {
            startLogFragment();
            navigationView.setCheckedItem(R.id.nav_logs);
        } else if (MAIN_ACTIVITY_START_IMPORT.equals(action)) {
            startConfigImportFlow();
        } else if (MAIN_ACTIVITY_START_EXPORT.equals(action)) {
            startConfigExportFlow();
        } else {
            handled = false;
        }

        if (handled) {
            // Do not leave a command action attached to the Activity. In particular,
            // freeform/split-screen resizing may recreate the Activity and otherwise
            // replay an already-consumed import/export command.
            Intent consumedIntent = new Intent(intent);
            consumedIntent.setAction(Intent.ACTION_MAIN);
            setIntent(consumedIntent);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_NAVIGATION_ITEM_BEFORE_LOG, navigationItemBeforeLog);
        outState.putBoolean(STATE_RESTORE_NAVIGATION_AFTER_LOG,
                restoreNavigationItemAfterLog);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        requestPermissions();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(RuntimeConfiguration.attach(this, newBase));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Fragment currentFragment = getCurrentFragment();
        fragment = currentFragment;
        if (item.getItemId() == android.R.id.home
                && !(currentFragment instanceof FileExplorerFragment)) {
            drawer.openDrawer(GravityCompat.START);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    public void openNavigationDrawer() {
        drawer.openDrawer(GravityCompat.START);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // result from file picker (for importing config file)
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri;
            if (data != null) {
                uri = data.getData();
                new CopyConfigFile().execute(uri);
            }
        } else if (requestCode == SETTINGS_CODE && resultCode == RESULT_OK) {
            boolean themeChanged = data.getBooleanExtra(SettingsActivity.THEME_CHANGED, false);
            if (themeChanged) {
                recreate();
            }
        } else if (requestCode == WRITE_REQUEST_CODE && resultCode == RESULT_OK) {
            Uri uri;
            if (data != null) {
                uri = data.getData();
                try {
                    rclone.exportConfigFile(uri);
                } catch (IOException e) {
                    FLog.e(TAG, "Could not export config file to %s", e, uri);
                    Toasty.error(this, getString(R.string.error_exporting_config_file), Toast.LENGTH_SHORT, true).show();
                }
            }
        } else if (requestCode == FileExplorerFragment.STREAMING_INTENT_RESULT) {
            Intent serveIntent = new Intent(this, StreamingService.class);
            context.stopService(serveIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < permissions.length; i++) {
            switch (permissions[i]) {
                case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                    // add/remove path aliases, depending on availability
                    RefreshLocalAliases refresh = new RefreshLocalAliases();
                    if (refresh.isRequired()) {
                        refresh.execute();
                    }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // TODO: document deletion on exit
        File dir = getExternalCacheDir();
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            for (String aChildren : children) {
                new File(dir, aChildren).delete();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Always hide search icon when fragments go back
        View searchButton = this.findViewById(R.id.searchButton);
        searchButton.setVisibility(View.INVISIBLE);

        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }

        Fragment currentFragment = getCurrentFragment();
        fragment = currentFragment;
        if (currentFragment instanceof FileExplorerFragment) {
            if (((FileExplorerFragment) currentFragment).onBackButtonPressed()) {
                return;
            }
        } else if (currentFragment instanceof TasksFragment
                || currentFragment instanceof TriggerFragment) {
            startRemotesFragment();
            return;
        }

        // A LogFragment opened as an overlay has a named back-stack entry, so
        // the normal FragmentManager back action reveals the retained page and,
        // for FileExplorerFragment, its exact current remote directory.
        super.onBackPressed();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        // Handle navigation view item clicks here.

        // Always hide search icon when fragments go back
        View searchButton = this.findViewById(R.id.searchButton);
        searchButton.setVisibility(View.INVISIBLE);

        int previouslyCheckedItemId = getCheckedNavigationItemId();
        int id = item.getItemId();
        navigationView.setCheckedItem(id);

        // set the last used fragment on each call.
        switch (id) {
            case R.id.nav_remotes:
            case R.id.nav_tasks:
            case R.id.nav_trigger:
            case R.id.nav_logs:
            case R.id.nav_permissions:
                SharedPreferencesUtil.Companion.setLastOpenFragment(this, id);
                break;
            case R.id.nav_import:
            case R.id.nav_export:
                SharedPreferencesUtil.Companion.setLastOpenFragment(this, R.id.nav_remotes);
                break;
        }

        // Log is a lightweight overlay over the page that opened it. Selecting
        // that same drawer destination again should simply reveal the retained
        // page instead of destroying and rebuilding its RecyclerView hierarchy.
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment instanceof LogFragment
                && hasBackStackEntry(LOG_BACK_STACK_NAME)
                && id == navigationItemBeforeLog) {
            getSupportFragmentManager().popBackStack(
                    LOG_BACK_STACK_NAME, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            drawer.closeDrawer(GravityCompat.START);
            return true;
        }

        if (drawerPinnedRemoteIds.containsKey(id)) {
            startPinnedRemote(drawerPinnedRemoteIds.get(id));
            return true;
        }

        if (id == R.id.nav_logs) {
            startLogFragment(previouslyCheckedItemId);
        } else {
            startFragmentById(id);
        }
        // Re-apply the requested destination after navigation bookkeeping.
        navigationView.setCheckedItem(id);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void startFragmentById(int id){
        switch (id) {
            case R.id.nav_remotes:
                startRemotesFragment();
                break;
            case R.id.nav_tasks:
                startTasksFragment();
                break;
            case R.id.nav_trigger:
                startTriggerFragment();
                break;
            case R.id.nav_logs:
                startLogFragment();
                break;
            case R.id.nav_permissions:
                startPermissionFragment();
                break;
        }
    }

    private void startConfigExportFlow() {
        if (rclone.isConfigFileCreated()) {
            exportConfigFile();
        } else {
            Toasty.info(this,  getString(R.string.no_config_found), Toast.LENGTH_SHORT, true).show();
        }
    }

    private void startConfigImportFlow() {
        Uri configUri;
        if (rclone.isConfigFileCreated()) {
            warnUserAboutOverwritingConfiguration();
        } else if(null != (configUri = rclone.searchExternalConfig())) {
            askUseExternalConfig(configUri);
        } else {
            importConfigFile();
        }
    }

    private void startTasksFragment(){
        startFragment(TasksFragment.newInstance());
    }

    private void startTriggerFragment() {
        startFragment(TriggerFragment.newInstance());
    }

    private void startLogFragment() {
        startLogFragment(getCheckedNavigationItemId());
    }

    private void startLogFragment(int navigationItemToRestore) {
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment instanceof LogFragment) {
            fragment = currentFragment;
            prepareActivityChromeForTopLevelFragment();
            navigationView.setCheckedItem(R.id.nav_logs);
            return;
        }

        LogFragment logFragment = LogFragment.newInstance();
        fragment = logFragment;
        FragmentManager fragmentManager = getSupportFragmentManager();

        if (currentFragment instanceof FileExplorerFragment
                || currentFragment instanceof RemotesFragment) {
            navigationItemBeforeLog = navigationItemToRestore;
            restoreNavigationItemAfterLog = true;
            if (!isFinishing()) {
                // Keep the current page and its complete view hierarchy alive.
                // Replacing it here forced RecyclerView, thumbnails and dynamic
                // Grid rows to be recreated when returning from logs.
                fragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .hide(currentFragment)
                        .setMaxLifecycle(currentFragment, Lifecycle.State.STARTED)
                        .add(R.id.flFragment, logFragment, LOG_FRAGMENT_TAG)
                        .setPrimaryNavigationFragment(logFragment)
                        .addToBackStack(LOG_BACK_STACK_NAME)
                        .commitAllowingStateLoss();
            }
        } else {
            restoreNavigationItemAfterLog = false;
            clearFragmentBackStack(fragmentManager);
            fragment = logFragment;
            if (!isFinishing()) {
                fragmentManager.beginTransaction()
                        .replace(R.id.flFragment, logFragment, LOG_FRAGMENT_TAG)
                        .commitAllowingStateLoss();
            }
        }
        prepareActivityChromeForTopLevelFragment();
        navigationView.setCheckedItem(R.id.nav_logs);
    }

    private void startPermissionFragment() {
        startFragment(PermissionFragment.Companion.newInstance());
    }

    private void startFragment(Fragment fragmentToStart) {
        restoreNavigationItemAfterLog = false;
        fragment = fragmentToStart;
        FragmentManager fragmentManager = getSupportFragmentManager();
        clearFragmentBackStack(fragmentManager);
        fragment = fragmentToStart;

        if (!isFinishing()) {
            fragmentManager.beginTransaction().replace(R.id.flFragment, fragment).commitAllowingStateLoss();
        }
    }

    private void clearFragmentBackStack(FragmentManager fragmentManager) {
        if (fragmentManager.getBackStackEntryCount() == 0
                || fragmentManager.isStateSaved()) {
            return;
        }
        // Pop synchronously so the following top-level replacement cannot be
        // interleaved with restoration of an older fragment.
        fragmentManager.popBackStackImmediate(
                null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    private Fragment getCurrentFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.flFragment);
    }

    private int getCheckedNavigationItemId() {
        MenuItem checkedItem = navigationView.getCheckedItem();
        return checkedItem == null ? R.id.nav_remotes : checkedItem.getItemId();
    }

    private boolean hasBackStackEntry(String entryName) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
            FragmentManager.BackStackEntry entry = fragmentManager.getBackStackEntryAt(i);
            if (entryName.equals(entry.getName())) {
                return true;
            }
        }
        return false;
    }

    private void setCheckedNavigationItemIfPresent(int itemId) {
        if (navigationView.getMenu().findItem(itemId) != null) {
            navigationView.setCheckedItem(itemId);
        } else {
            navigationView.setCheckedItem(R.id.nav_remotes);
        }
    }

    private void prepareActivityChromeForTopLevelFragment() {
        View breadcrumbView = findViewById(R.id.breadcrumb_view);
        View searchBar = findViewById(R.id.search_bar);
        View searchButton = findViewById(R.id.searchButton);
        if (breadcrumbView != null) {
            breadcrumbView.setVisibility(View.GONE);
        }
        if (searchBar != null) {
            searchBar.setVisibility(View.INVISIBLE);
        }
        if (searchButton != null) {
            searchButton.setVisibility(View.INVISIBLE);
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }
    }

    private void syncCurrentFragmentAfterNavigation() {
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment == null) {
            return;
        }
        fragment = currentFragment;

        if (!(currentFragment instanceof FileExplorerFragment)) {
            prepareActivityChromeForTopLevelFragment();
        }

        if (currentFragment instanceof LogFragment) {
            navigationView.setCheckedItem(R.id.nav_logs);
        } else if (currentFragment instanceof FileExplorerFragment) {
            ((FileExplorerFragment) currentFragment).restoreActivityChrome();
            if (restoreNavigationItemAfterLog && !hasBackStackEntry(LOG_BACK_STACK_NAME)) {
                setCheckedNavigationItemIfPresent(navigationItemBeforeLog);
                restoreNavigationItemAfterLog = false;
            }
        } else if (currentFragment instanceof RemotesFragment) {
            setTitle(R.string.app_name);
            if (restoreNavigationItemAfterLog && !hasBackStackEntry(LOG_BACK_STACK_NAME)) {
                setCheckedNavigationItemIfPresent(navigationItemBeforeLog);
                restoreNavigationItemAfterLog = false;
            } else {
                navigationView.setCheckedItem(R.id.nav_remotes);
            }
        } else if (currentFragment instanceof TasksFragment) {
            navigationView.setCheckedItem(R.id.nav_tasks);
        } else if (currentFragment instanceof TriggerFragment) {
            navigationView.setCheckedItem(R.id.nav_trigger);
        } else if (currentFragment instanceof PermissionFragment) {
            navigationView.setCheckedItem(R.id.nav_permissions);
        }
    }

    private void pinRemotesToDrawer() {
        Menu menu = navigationView.getMenu();
        MenuItem existingMenu = menu.findItem(1);
        if (existingMenu != null) {
            return;
        }

        SubMenu subMenu = menu.addSubMenu(R.id.drawer_pinned_header, 1, Menu.NONE, R.string.nav_drawer_pinned_header);

        List<RemoteItem> remoteItems = rclone.getRemotes();
        Collections.sort(remoteItems);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> renamedRemotes = sharedPreferences.getStringSet(getString(R.string.pref_key_renamed_remotes), new HashSet<>());
        for(RemoteItem item : remoteItems) {
            if(renamedRemotes.contains(item.getName())) {
                String displayName = sharedPreferences.getString(
                        getString(R.string.pref_key_renamed_remote_prefix, item.getName()), item.getName());
                item.setDisplayName(displayName);
            }
        }
        for (RemoteItem remoteItem : remoteItems) {
            if (remoteItem.isDrawerPinned()) {
                MenuItem menuItem = subMenu.add(R.id.nav_pinned, availableDrawerPinnedRemoteId, Menu.NONE, remoteItem.getDisplayName());
                drawerPinnedRemoteIds.put(availableDrawerPinnedRemoteId, remoteItem);
                availableDrawerPinnedRemoteId++;
                menuItem.setIcon(remoteItem.getRemoteIcon());
            }
        }
    }

    public void startRemotesFragment() {
        restoreNavigationItemAfterLog = false;
        FragmentManager fragmentManager = getSupportFragmentManager();
        clearFragmentBackStack(fragmentManager);
        fragment = RemotesFragment.newInstance();

        if (!isFinishing()) {
            fragmentManager.beginTransaction().replace(R.id.flFragment, fragment).commitAllowingStateLoss();
        }
        navigationView.setCheckedItem(R.id.nav_remotes);
    }

    private void warnUserAboutOverwritingConfiguration() {
        if (overwriteConfigurationDialog != null && overwriteConfigurationDialog.isShowing()) {
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog);
        builder.setTitle(R.string.replace_config_file_question);
        builder.setMessage(R.string.config_file_lost_statement);
        builder.setPositiveButton(R.string.continue_statement, (dialogInterface, i) -> {
            dialogInterface.dismiss();
            Uri configUri;
            if(null != (configUri = rclone.searchExternalConfig())){
                askUseExternalConfig(configUri);
            } else {
                importConfigFile();
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> dialogInterface.dismiss());
        overwriteConfigurationDialog = builder.create();
        overwriteConfigurationDialog.setOnDismissListener(ignored -> overwriteConfigurationDialog = null);
        overwriteConfigurationDialog.show();
    }

    public void askUseExternalConfig(final Uri uri) {
        if (externalConfigurationDialog != null && externalConfigurationDialog.isShowing()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.config_use_external_question);
        builder.setMessage(context.getString(R.string.config_import_external_explain, uri.toString()));
        builder.setPositiveButton(R.string.continue_statement, (dialogInterface, i) -> {
            dialogInterface.dismiss();
            new CopyConfigFile().execute(uri);
        });
        builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
            dialogInterface.dismiss();
            importConfigFile();
        });
        externalConfigurationDialog = builder.create();
        externalConfigurationDialog.setOnDismissListener(ignored -> externalConfigurationDialog = null);
        externalConfigurationDialog.show();
    }

    private void askForConfigPassword() {
        findViewById(R.id.locked_config).setVisibility(View.VISIBLE);
        new InputDialog()
                .setTitle(R.string.config_password_protected)
                .setMessage(R.string.please_enter_password)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.okay_confirmation)
                .setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .show(getSupportFragmentManager(), "input dialog");
    }

    /*
     * Input Dialog callback
     */
    @Override
    public void onPositive(String tag, String input) {
        new DecryptConfig().execute(input);
    }

    public void importConfigFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        tryStartActivityForResult(this, intent, READ_REQUEST_CODE);
    }

    public void exportConfigFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd_HHmmss");
        String localTime = date.format(Calendar.getInstance().getTime());

        intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.app_name)+"-backup-"+localTime+".zip");
        tryStartActivityForResult(this, intent, WRITE_REQUEST_CODE);
    }

    public void requestPermissions() {
        boolean refreshLocalAliases = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(getString(R.string.pref_key_refresh_local_aliases), true);
        if (refreshLocalAliases) {
            FLog.d(TAG, "Reloading local path aliases");
            RefreshLocalAliases refresh = new RefreshLocalAliases();
            if (refresh.isRequired()) {
                refresh.execute();
            }
        }
    }

    private void updatePermissionFragmentVisibility() {
        Menu navMenu = navigationView.getMenu();
        if((new PermissionManager(this).hasAllPermissions())) {
            navMenu.findItem(R.id.nav_permissions).setVisible(false);
        } else {
            navMenu.findItem(R.id.nav_permissions).setVisible(true);
        }

    }

    @Override
    public void onRemoteClick(RemoteItem remote) {
        startRemote(remote, true);
    }

    private void startRemote(RemoteItem remote, boolean addToBackStack) {
        restoreNavigationItemAfterLog = false;
        fragment = FileExplorerFragment.newInstance(remote);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.flFragment, fragment, FILE_EXPLORER_FRAGMENT_TAG);
        if (addToBackStack) {
            transaction.addToBackStack(null);
        }
        transaction.commit();

        AppShortcutsHelper.reportAppShortcutUsage(this, remote.getName());
        //navigationView.getMenu().getItem(0).setChecked(false);
    }

    private void startPinnedRemote(RemoteItem remoteItem) {
        int pinnedNavigationItemId = getCheckedNavigationItemId();
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = getCurrentFragment();

        // A pinned remote is a new navigation destination, not a child of the
        // temporary log page. Remove the log overlay first so Back never returns
        // to LogFragment after switching remotes from the drawer.
        if (currentFragment instanceof LogFragment
                && hasBackStackEntry(LOG_BACK_STACK_NAME)) {
            restoreNavigationItemAfterLog = false;
            clearFragmentBackStack(fragmentManager);
            currentFragment = getCurrentFragment();
        }

        fragment = currentFragment;
        if (currentFragment instanceof FileExplorerFragment) {

            // this is the case when remote gets started from a shortcut
            // therefore back should exit the app, and not go into remotes screen
            if (fragmentManager.getBackStackEntryCount() == 0) {
                startRemote(remoteItem, false);
            } else {
                clearFragmentBackStack(fragmentManager);
                startRemote(remoteItem, true);
            }
        } else {
            startRemote(remoteItem, true);
        }

        setCheckedNavigationItemIfPresent(pinnedNavigationItemId);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
    }

    @Override
    public void addRemoteToNavDrawer() {
        Menu menu = navigationView.getMenu();

        // remove all items and add them again so that it's in alpha order
        menu.removeItem(1);
        drawerPinnedRemoteIds.clear();
        availableDrawerPinnedRemoteId = 1;

        pinRemotesToDrawer();
    }

    @Override
    public void removeRemoteFromNavDrawer() {
        Menu menu = navigationView.getMenu();

        // remove all items and add them again so that it's in alpha order
        menu.removeItem(1);
        drawerPinnedRemoteIds.clear();
        availableDrawerPinnedRemoteId = 1;

        pinRemotesToDrawer();
    }

    @SuppressLint("StaticFieldLeak")
    private class CopyConfigFile extends AsyncTask<Uri, Void, Boolean> {

        private final int SUCCESS_IMPORT = 0;
        private final int FAILURE_UNSPECIFIED = 1;
        private final int FAILURE_RCLONE_CONF_NOT_VALID = 2;
        private final int FAILURE_ZIP_NO_JSON = 3;
        private final int FAILURE_ZIP_INVALID_JSON = 4;
        private final int FAILURE_ZIP_INVALID_CONF = 5;
        private final int FAILURE_ZIP_MISSING_CONF = 6;

        private int statusCode = FAILURE_UNSPECIFIED;
        private LoadingDialog loadingDialog;
        private boolean importedConfigurationPackage;


        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            findViewById(R.id.locked_config).setVisibility(View.GONE);
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.copying_rclone_config)
                    .setCanCancel(false);
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Boolean doInBackground(Uri... uris) {
            Uri source = uris[0];
            ContentResolver resolver = context.getContentResolver();
            String mime = resolver.getType(source);
            String sourcePath = source.getPath();
            boolean isZipPackage = "application/zip".equals(mime)
                    || "application/x-zip-compressed".equals(mime)
                    || (sourcePath != null && sourcePath.toLowerCase(java.util.Locale.US).endsWith(".zip"))
                    || rclone.isZipPackage(source);

            if (isZipPackage) {
                final String databaseJson;
                final String preferencesJson;
                final String quarkDavJson;
                try {
                    // Read every component before touching the active configuration.
                    databaseJson = rclone.readDatabaseJson(source);
                    preferencesJson = rclone.readSharedPrefs(source);
                    quarkDavJson = rclone.readQuarkDavJson(source);
                } catch (Exception error) {
                    statusCode = FAILURE_ZIP_NO_JSON;
                    return false;
                }

                try {
                    Importer.validateJson(databaseJson);
                    SharedPreferencesBackup.validateJson(preferencesJson);
                    if (quarkDavJson != null) {
                        QuarkDavRepository.validateBackup(quarkDavJson);
                    }
                } catch (JSONException | RuntimeException error) {
                    statusCode = FAILURE_ZIP_INVALID_JSON;
                    return false;
                }

                try {
                    if (!rclone.validateConfigFileFromZip(source)) {
                        statusCode = FAILURE_ZIP_INVALID_CONF;
                        return false;
                    }
                } catch (IOException error) {
                    statusCode = FAILURE_ZIP_MISSING_CONF;
                    return false;
                }

                final String previousDatabaseJson;
                final String previousPreferencesJson;
                final String previousQuarkDavJson;
                try {
                    previousDatabaseJson = Exporter.create(context);
                    previousPreferencesJson = SharedPreferencesBackup.export(context);
                    previousQuarkDavJson = QuarkDavRepository.exportBackup(context);
                } catch (Exception error) {
                    statusCode = FAILURE_UNSPECIFIED;
                    return false;
                }

                try {
                    Importer.importJson(databaseJson, context);
                    SharedPreferencesBackup.importJson(preferencesJson, context);
                    if (quarkDavJson != null) {
                        QuarkDavRepository.replaceFromBackup(context, quarkDavJson);
                    }
                } catch (JSONException | RuntimeException error) {
                    restoreMetadata(previousDatabaseJson, previousPreferencesJson, previousQuarkDavJson);
                    statusCode = FAILURE_ZIP_INVALID_JSON;
                    return false;
                }

                try {
                    if (!rclone.copyConfigFileFromZip(source)) {
                        restoreMetadata(previousDatabaseJson, previousPreferencesJson, previousQuarkDavJson);
                        statusCode = FAILURE_ZIP_INVALID_CONF;
                        return false;
                    }
                } catch (Exception error) {
                    restoreMetadata(previousDatabaseJson, previousPreferencesJson, previousQuarkDavJson);
                    statusCode = FAILURE_ZIP_MISSING_CONF;
                    return false;
                }

                importedConfigurationPackage = true;
                statusCode = SUCCESS_IMPORT;
                return true;
            }

            try {
                boolean validRclone = rclone.copyConfigFile(source);
                if (validRclone) {
                    statusCode = SUCCESS_IMPORT;
                    return true;
                }
                statusCode = FAILURE_RCLONE_CONF_NOT_VALID;
                return false;
            } catch (IOException e) {
                statusCode = FAILURE_RCLONE_CONF_NOT_VALID;
                return false;
            }
        }

        private void restoreMetadata(
                String databaseJson,
                String preferencesJson,
                String quarkDavJson) {
            try {
                Importer.importJson(databaseJson, context);
                SharedPreferencesBackup.importJson(preferencesJson, context);
                QuarkDavRepository.replaceFromBackup(context, quarkDavJson);
            } catch (Exception rollbackError) {
                FLog.e(TAG, "Unable to roll back configuration import", rollbackError);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            Dialogs.dismissSilently(loadingDialog);

            String errorMessage = getString(R.string.copying_rclone_config_fail);
            if (!success) {
                switch (statusCode) {
                    case FAILURE_RCLONE_CONF_NOT_VALID:
                    case FAILURE_ZIP_INVALID_CONF:
                        errorMessage = getString(R.string.import_configuration_fail_rclone);
                        break;
                    case FAILURE_ZIP_MISSING_CONF:
                        errorMessage = getString(R.string.import_configuration_fail_zip_conf_missing);
                        break;
                    case FAILURE_ZIP_NO_JSON:
                    case FAILURE_ZIP_INVALID_JSON:
                        errorMessage = getString(R.string.import_configuration_fail_zip_json_missing);
                        break;
                }
                Toasty.error(context, errorMessage, Toast.LENGTH_LONG, true).show();
                return;
            }

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            if(sharedPreferences.getBoolean(getString(R.string.pref_key_enable_saf), false)){
                RemoteConfigHelper.enableSaf(context);
            }

            if (!importedConfigurationPackage) {
                // A standalone rclone.conf has no matching UI metadata, so discard only
                // remote-specific selections that can now point at non-existent remotes.
                sharedPreferences.edit()
                        .remove(getString(R.string.shared_preferences_pinned_remotes))
                        .remove(getString(R.string.shared_preferences_drawer_pinned_remotes))
                        .remove(getString(R.string.shared_preferences_hidden_remotes))
                        .apply();
            }

            // Apply the imported desired state after all metadata and preferences exist.
            QuarkDavServiceActions.INSTANCE.reconcile(context);

            if (rclone.isConfigEncrypted()) {
                restoreImportedShortcutSelectionAfterDecrypt = importedConfigurationPackage;
                pinRemotesToDrawer(); // this will clear any previous pinned remotes
                askForConfigPassword();
            } else {
                if (importedConfigurationPackage) {
                    AppShortcutsHelper.syncConfiguredAppShortcuts(context, rclone.getRemotes());
                } else {
                    AppShortcutsHelper.removeAllAppShortcuts(context);
                    AppShortcutsHelper.populateAppShortcuts(context, rclone.getRemotes());
                }
                pinRemotesToDrawer();
                startRemotesFragment();
            }
        }
    }

    private class RefreshLocalAliases extends AsyncTask<Void, Void, Boolean> {

        private String EMULATED = "5d44cd8d-397c-4107-b79b-17f2b6a071e8";

        private LoadingDialog loadingDialog;

        private boolean isPermissable(File file) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                return Environment.isExternalStorageLegacy(file);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager(file);
            } else {
                return true;
            }
        }

        protected boolean isRequired() {
            String[] externalVolumes = null;
            String persisted = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(getString(R.string.pref_key_accessible_storage_locations), null);
            if(null != persisted) {
                externalVolumes = persisted.split("\\|");
            }
            String[] current = Stream.of(context.getExternalFilesDirs(null))
                    .filter(f -> f != null)
                    .map(this::getRootOrSelf)
                    .filter(this::isPermissable)
                    .map(File::getAbsolutePath)
                    .toArray(String[]::new);

            if(Arrays.deepEquals(externalVolumes, current)) {
                FLog.d(TAG, "Storage volumes not changed, no refresh required");
                return false;
            } else {
                FLog.d(TAG, "Storage volumnes changed, refresh required");
                externalVolumes = current;
                persisted = TextUtils.join("|", current);
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(getString(R.string.pref_key_accessible_storage_locations), persisted).apply();
                return true;
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if(isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
                FLog.w(TAG, "Invalid state, stopping drive refresh");
                cancel(true);
                return;
            }
            View v = findViewById(R.id.locked_config);
            if (v != null) {
                v.setVisibility(View.GONE);
            }
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.refreshing_local_alias_remotes)
                    .setCanCancel(false);
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Boolean doInBackground(Void... aVoid) {
            if (isCancelled()) {
                return null;
            }
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            Set<String> generated = pref.getStringSet(getString(R.string.pref_key_local_alias_remotes), new HashSet<>());
            Set<String> renamed = pref.getStringSet(getString(R.string.pref_key_renamed_remotes), new HashSet<>());
            SharedPreferences.Editor editor = pref.edit();
            for(String remote : generated) {
                rclone.deleteRemote(remote);
                renamed.remove(remote);
                editor.remove(getString(R.string.pref_key_renamed_remote_prefix, remote));
            }
            editor.putStringSet(getString(R.string.pref_key_renamed_remotes), renamed);
            editor.apply();
            File[] dirs = context.getExternalFilesDirs(null);
            for(File file : dirs) {
                // May be null if the path is currently not available
                if (null == file) {
                    continue;
                }
                if (file.getPath().contains(BuildConfig.APPLICATION_ID)) {
                    try {
                        File root = getVolumeRoot(file);
                        if (root.canRead()) {
                            addLocalRemote(root);
                        } else if (file.canRead()){
                            addLocalRemote(file);
                        }
                    } catch (NullPointerException | IOException e) {
                        // ignored, this is not a valid file
                        FLog.w(TAG, "doInBackground: could not read file", e);
                    }
                }
            }
            return null;
        }

        private File getRootOrSelf(File file) {
            File root = getVolumeRoot(file);
            if (root.canRead()) {
                return root;
            } else {
                return file;
            }
        }

        private File getVolumeRoot(File file) {
            String path = file.getAbsolutePath();
            int levelsUp = 0;
            int index = path.length();
            while(levelsUp++ <= 3) {
                index = path.lastIndexOf('/', index-1);
            }
            return new File(path.substring(0, index));
        }

        private void addLocalRemote(File root) throws IOException {
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String name = root.getCanonicalPath();
            String id = UUID.randomUUID().toString();
            try {
                if (Environment.isExternalStorageEmulated(root)) {
                    id = EMULATED;
                }
            } catch (IllegalArgumentException e) {
                FLog.e(TAG, "RefreshLocalAliases/addLocalRemote: %s is not a valid path", e, name);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                StorageVolume storageVolume = storageManager.getStorageVolume(root);
                if (null != storageVolume) {
                    name = storageVolume.getDescription(context);
                    if (null != storageVolume.getUuid()) {
                        id = storageVolume.getUuid();
                    }
                }
            }

            String path = root.getAbsolutePath();
            ArrayList<String> options = new ArrayList<>();
            options.add(id);
            options.add("alias");
            options.add("remote");
            options.add(path);
            FLog.d(TAG, "Adding local remote [%s] remote = %s", id, path);
            Process process = rclone.configCreate(options);
            if (null == process) {
                return;
            }
            try {
                process.waitFor();
                if (process.exitValue() != 0) {
                    FLog.w(TAG, "addLocalRemote: process error");
                    return;
                }
            } catch (InterruptedException e) {
                FLog.e(TAG, "addLocalRemote: process error", e);
                return;
            }
            Set<String> renamedRemotes = pref.getStringSet(getString(R.string.pref_key_renamed_remotes), new HashSet<>());
            Set<String> pinnedRemotes = pref.getStringSet(getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());
            Set<String> generatedRemotes = pref.getStringSet(getString(R.string.pref_key_local_alias_remotes), new HashSet<>());
            renamedRemotes.add(id);
            pinnedRemotes.add(id);
            generatedRemotes.add(id);
            pref.edit()
                    .putStringSet(getString(R.string.pref_key_renamed_remotes), renamedRemotes)
                    .putString(getString(R.string.pref_key_renamed_remote_prefix, id), name)
                    .putStringSet(getString(R.string.shared_preferences_drawer_pinned_remotes), pinnedRemotes)
                    .putStringSet(getString(R.string.pref_key_local_alias_remotes), generatedRemotes)
                    .apply();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            Dialogs.dismissSilently(loadingDialog);
            AppShortcutsHelper.removeAllAppShortcuts(context);
            AppShortcutsHelper.populateAppShortcuts(context, rclone.getRemotes());
            pinRemotesToDrawer();

            if (fragment instanceof RemotesFragment) {
                startRemotesFragment();
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class DecryptConfig extends AsyncTask<String, Void, Boolean> {

        private LoadingDialog loadingDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.working)
                    .setCanCancel(false);
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            return rclone.decryptConfig(strings[0]);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            Dialogs.dismissSilently(loadingDialog);
            if (!success) {
                Toasty.error(context, getString(R.string.error_unlocking_config), Toast.LENGTH_LONG, true).show();
                askForConfigPassword();
            } else {
                findViewById(R.id.locked_config).setVisibility(View.GONE);
                if (restoreImportedShortcutSelectionAfterDecrypt) {
                    AppShortcutsHelper.syncConfiguredAppShortcuts(context, rclone.getRemotes());
                    restoreImportedShortcutSelectionAfterDecrypt = false;
                } else {
                    AppShortcutsHelper.removeAllAppShortcuts(context);
                    AppShortcutsHelper.populateAppShortcuts(context, rclone.getRemotes());
                }
            }
        }
    }
}
