package ca.pkay.rcloneexplorer.Settings

import android.content.DialogInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.AppShortcutsHelper
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.thumbnails.ThumbnailRepository
import de.felixnuesse.extract.settings.language.LanguagePicker
import de.felixnuesse.extract.settings.preferences.EditIntPreference
import es.dmoral.toasty.Toasty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeneralPreferencesFragment : PreferenceFragmentCompat() {

    private data class ThumbnailCacheUi(
        val type: ThumbnailRepository.CacheType,
        val limitKey: Int,
        val targetKey: Int,
        val defaultLimit: Int,
        val defaultTarget: Int,
        val statisticsKey: Int,
        val importKey: Int,
        val exportKey: Int,
        val clearKey: Int,
        val importConfirmTitle: Int,
        val importingMessage: Int,
        val exportingMessage: Int,
        val clearConfirmTitle: Int,
        val clearConfirmMessage: Int,
        val clearDoneMessage: Int,
        val exportFilePrefix: String
    )

    private val imageCacheUi = ThumbnailCacheUi(
        ThumbnailRepository.CacheType.IMAGE,
        R.string.pref_key_thumbnail_image_disk_cache_max_mb,
        R.string.pref_key_thumbnail_image_disk_cache_trim_target_mb,
        R.integer.default_thumbnail_image_disk_cache_max_mb,
        R.integer.default_thumbnail_image_disk_cache_trim_target_mb,
        R.string.pref_key_thumbnail_image_cache_statistics,
        R.string.pref_key_import_thumbnail_image_cache,
        R.string.pref_key_export_thumbnail_image_cache,
        R.string.pref_key_clear_thumbnail_image_cache,
        R.string.pref_import_thumbnail_image_cache_confirm_title,
        R.string.pref_thumbnail_image_cache_importing,
        R.string.pref_thumbnail_image_cache_exporting,
        R.string.pref_clear_thumbnail_image_cache_confirm_title,
        R.string.pref_clear_thumbnail_image_cache_confirm_message,
        R.string.pref_clear_thumbnail_image_cache_done,
        "Round-Sync-image-thumbnails"
    )

    private val videoCacheUi = ThumbnailCacheUi(
        ThumbnailRepository.CacheType.VIDEO,
        R.string.pref_key_thumbnail_video_disk_cache_max_mb,
        R.string.pref_key_thumbnail_video_disk_cache_trim_target_mb,
        R.integer.default_thumbnail_video_disk_cache_max_mb,
        R.integer.default_thumbnail_video_disk_cache_trim_target_mb,
        R.string.pref_key_thumbnail_video_cache_statistics,
        R.string.pref_key_import_thumbnail_video_cache,
        R.string.pref_key_export_thumbnail_video_cache,
        R.string.pref_key_clear_thumbnail_video_cache,
        R.string.pref_import_thumbnail_video_cache_confirm_title,
        R.string.pref_thumbnail_video_cache_importing,
        R.string.pref_thumbnail_video_cache_exporting,
        R.string.pref_clear_thumbnail_video_cache_confirm_title,
        R.string.pref_clear_thumbnail_video_cache_confirm_message,
        R.string.pref_clear_thumbnail_video_cache_done,
        "Round-Sync-video-thumbnails"
    )

    private val loadedThumbnailStatistics = HashSet<ThumbnailRepository.CacheType>()

    private val importImageThumbnailCacheLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && isAdded) {
            confirmThumbnailCacheImport(imageCacheUi, uri)
        }
    }

    private val importVideoThumbnailCacheLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && isAdded) {
            confirmThumbnailCacheImport(videoCacheUi, uri)
        }
    }

    private val exportImageThumbnailCacheLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && isAdded) {
            exportThumbnailCache(imageCacheUi, uri)
        }
    }

    private val exportVideoThumbnailCacheLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && isAdded) {
            exportThumbnailCache(videoCacheUi, uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_general_preferences, rootKey)
        requireActivity().title = getString(R.string.pref_header_general)

        configureThumbnailPreferences()

        val shortcutsPreference = findPreference("AppShortcutTempKey") as Preference?
        shortcutsPreference?.setOnPreferenceClickListener {
            showAppShortcutDialog()
            true
        }

        val languagePreference = findPreference("languagePickerTempKey") as Preference?
        languagePreference?.setSummary(LanguagePicker(requireContext()).getCurrentLocale()?.displayLanguage)
        languagePreference?.setOnPreferenceClickListener {
            LanguagePicker(requireContext()).showPicker()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshThumbnailCacheStatistics(imageCacheUi)
        refreshThumbnailCacheStatistics(videoCacheUi)
    }

    private fun configureThumbnailPreferences() {
        // Ensure the repository preference listener is active even if settings opens first.
        ThumbnailRepository.getInstance(requireContext())

        val summaryProvider = Preference.SummaryProvider<EditIntPreference> { preference ->
            getString(R.string.pref_thumbnail_size_mb_summary, preference.getValue())
        }
        val imageSourceLimitPreference = findPreference<EditIntPreference>(
            getString(R.string.pref_key_thumbnail_image_source_max_mb)
        )
        imageSourceLimitPreference?.summaryProvider = summaryProvider
        imageSourceLimitPreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val limitMb = newValue.toString().toIntOrNull()
                if (limitMb == null || limitMb <= 0) {
                    showThumbnailValidationError(R.string.pref_thumbnail_value_positive)
                    false
                } else {
                    true
                }
            }

        configureThumbnailCachePreferences(imageCacheUi, summaryProvider)
        configureThumbnailCachePreferences(videoCacheUi, summaryProvider)
    }

    private fun configureThumbnailCachePreferences(
        ui: ThumbnailCacheUi,
        summaryProvider: Preference.SummaryProvider<EditIntPreference>
    ) {
        val cacheLimitPreference = findPreference<EditIntPreference>(getString(ui.limitKey))
        val cacheTargetPreference = findPreference<EditIntPreference>(getString(ui.targetKey))
        cacheLimitPreference?.summaryProvider = summaryProvider
        cacheTargetPreference?.summaryProvider = summaryProvider

        cacheLimitPreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val limitMb = newValue.toString().toIntOrNull()
                if (limitMb == null || limitMb <= 0) {
                    showThumbnailValidationError(R.string.pref_thumbnail_value_positive)
                    return@OnPreferenceChangeListener false
                }
                val targetMb = cacheTargetPreference?.getValue()
                    ?: resources.getInteger(ui.defaultTarget)
                if (limitMb < targetMb) {
                    showThumbnailValidationError(R.string.pref_thumbnail_cache_limit_below_target)
                    return@OnPreferenceChangeListener false
                }
                true
            }

        cacheTargetPreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val targetMb = newValue.toString().toIntOrNull()
                if (targetMb == null || targetMb <= 0) {
                    showThumbnailValidationError(R.string.pref_thumbnail_value_positive)
                    return@OnPreferenceChangeListener false
                }
                val limitMb = cacheLimitPreference?.getValue()
                    ?: resources.getInteger(ui.defaultLimit)
                if (targetMb > limitMb) {
                    showThumbnailValidationError(R.string.pref_thumbnail_cache_target_above_limit)
                    return@OnPreferenceChangeListener false
                }
                true
            }

        findPreference<Preference>(getString(ui.importKey))
            ?.setOnPreferenceClickListener {
                launchThumbnailCacheImport(ui.type)
                true
            }

        findPreference<Preference>(getString(ui.exportKey))
            ?.setOnPreferenceClickListener {
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd_HHmmss",
                    Locale.US
                ).format(Date())
                launchThumbnailCacheExport(ui.type, "${ui.exportFilePrefix}-$timestamp.zip")
                true
            }

        findPreference<Preference>(getString(ui.clearKey))
            ?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(ui.clearConfirmTitle)
                    .setMessage(ui.clearConfirmMessage)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        setThumbnailCacheActionsEnabled(ui, false)
                        ThumbnailRepository.getInstance(requireContext())
                            .clearCache(ui.type) clearCallback@{
                                if (!isAdded) return@clearCallback
                                setThumbnailCacheActionsEnabled(ui, true)
                                refreshThumbnailCacheStatistics(ui)
                                Toasty.success(
                                    requireContext(),
                                    getString(ui.clearDoneMessage),
                                    Toast.LENGTH_SHORT,
                                    true
                                ).show()
                            }
                    }
                    .show()
                true
            }
    }

    private fun launchThumbnailCacheImport(type: ThumbnailRepository.CacheType) {
        val mimeTypes = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
        )
        when (type) {
            ThumbnailRepository.CacheType.IMAGE ->
                importImageThumbnailCacheLauncher.launch(mimeTypes)
            ThumbnailRepository.CacheType.VIDEO ->
                importVideoThumbnailCacheLauncher.launch(mimeTypes)
        }
    }

    private fun launchThumbnailCacheExport(
        type: ThumbnailRepository.CacheType,
        fileName: String
    ) {
        when (type) {
            ThumbnailRepository.CacheType.IMAGE ->
                exportImageThumbnailCacheLauncher.launch(fileName)
            ThumbnailRepository.CacheType.VIDEO ->
                exportVideoThumbnailCacheLauncher.launch(fileName)
        }
    }

    private fun refreshThumbnailCacheStatistics(ui: ThumbnailCacheUi) {
        val statisticsKey = getString(ui.statisticsKey)
        val statisticsPreference = findPreference<Preference>(statisticsKey) ?: return
        if (!loadedThumbnailStatistics.contains(ui.type)) {
            statisticsPreference.summary =
                getString(R.string.pref_thumbnail_cache_statistics_loading)
        }

        ThumbnailRepository.getInstance(requireContext())
            .getCacheStatistics(ui.type) callback@{ statistics ->
                if (!isAdded) return@callback
                val currentStatisticsPreference = findPreference<Preference>(statisticsKey)
                    ?: return@callback
                val sizeMb = statistics.totalBytes / (1024.0 * 1024.0)
                loadedThumbnailStatistics.add(ui.type)
                currentStatisticsPreference.summary = getString(
                    R.string.pref_thumbnail_cache_statistics_summary,
                    statistics.thumbnailCount,
                    sizeMb
                )
            }
    }

    private fun confirmThumbnailCacheImport(ui: ThumbnailCacheUi, uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(ui.importConfirmTitle)
            .setMessage(R.string.pref_import_thumbnail_cache_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_statement) { _, _ ->
                importThumbnailCache(ui, uri)
            }
            .show()
    }

    private fun importThumbnailCache(ui: ThumbnailCacheUi, uri: Uri) {
        setThumbnailCacheActionsEnabled(ui, false)
        Toasty.info(
            requireContext(),
            getString(ui.importingMessage),
            Toast.LENGTH_SHORT,
            true
        ).show()
        ThumbnailRepository.getInstance(requireContext())
            .importCache(ui.type, uri) callback@{ result ->
                if (!isAdded) return@callback
                setThumbnailCacheActionsEnabled(ui, true)
                if (result.isSuccessful) {
                    refreshThumbnailCacheStatistics(ui)
                    Toasty.success(
                        requireContext(),
                        getString(
                            R.string.pref_thumbnail_cache_import_done,
                            result.addedCount,
                            result.replacedCount,
                            result.skippedCount
                        ),
                        Toast.LENGTH_LONG,
                        true
                    ).show()
                } else {
                    showThumbnailCacheArchiveError(result.error)
                }
            }
    }

    private fun exportThumbnailCache(ui: ThumbnailCacheUi, uri: Uri) {
        setThumbnailCacheActionsEnabled(ui, false)
        Toasty.info(
            requireContext(),
            getString(ui.exportingMessage),
            Toast.LENGTH_SHORT,
            true
        ).show()
        ThumbnailRepository.getInstance(requireContext())
            .exportCache(ui.type, uri) callback@{ result ->
                if (!isAdded) return@callback
                setThumbnailCacheActionsEnabled(ui, true)
                if (result.isSuccessful) {
                    val sizeMb = result.totalBytes / (1024.0 * 1024.0)
                    Toasty.success(
                        requireContext(),
                        getString(
                            R.string.pref_thumbnail_cache_export_done,
                            result.thumbnailCount,
                            sizeMb
                        ),
                        Toast.LENGTH_LONG,
                        true
                    ).show()
                } else {
                    showThumbnailCacheArchiveError(result.error)
                }
            }
    }

    private fun setThumbnailCacheActionsEnabled(ui: ThumbnailCacheUi, enabled: Boolean) {
        findPreference<Preference>(getString(ui.importKey))?.isEnabled = enabled
        findPreference<Preference>(getString(ui.exportKey))?.isEnabled = enabled
        findPreference<Preference>(getString(ui.clearKey))?.isEnabled = enabled
    }

    private fun showThumbnailCacheArchiveError(error: ThumbnailRepository.CacheArchiveError) {
        val message = when (error) {
            ThumbnailRepository.CacheArchiveError.INVALID_ARCHIVE ->
                R.string.pref_thumbnail_cache_archive_invalid
            ThumbnailRepository.CacheArchiveError.INCOMPATIBLE_CACHE_VERSION ->
                R.string.pref_thumbnail_cache_archive_incompatible
            ThumbnailRepository.CacheArchiveError.EMPTY_ARCHIVE ->
                R.string.pref_thumbnail_cache_archive_empty
            else -> R.string.pref_thumbnail_cache_archive_io_error
        }
        Toasty.error(requireContext(), getString(message), Toast.LENGTH_LONG, true).show()
    }

    private fun showThumbnailValidationError(message: Int) {
        Toasty.error(requireContext(), getString(message), Toast.LENGTH_SHORT, true).show()
    }

    private fun showAppShortcutDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(
            requireContext()
        )
        val appShortcuts = sharedPreferences.getStringSet(
            getString(R.string.shared_preferences_app_shortcuts),
            HashSet()
        )

        val builder = AlertDialog.Builder(
            requireContext()
        )
        builder.setTitle(R.string.app_shortcuts_settings_dialog_title)

        val rclone = Rclone(context)
        val remotes = ArrayList(rclone.remotes)
        RemoteItem.prepareDisplay(context, remotes)
        remotes.sortWith { a: RemoteItem, b: RemoteItem -> a.displayName.compareTo(b.displayName) }
        val options = arrayOfNulls<CharSequence>(remotes.size)
        var i = 0
        for (remoteItem in remotes) {
            options[i++] = remoteItem.displayName
        }

        val userSelected = ArrayList<String>()
        val checkedItems = BooleanArray(options.size)
        i = 0
        for (item in remotes) {
            val s = item.name.toString()
            val hash = AppShortcutsHelper.getUniqueIdFromString(s)
            if (appShortcuts?.contains(hash) == true) {
                userSelected.add(item.name.toString())
                checkedItems[i] = true
            }
            i++
        }

        builder.setMultiChoiceItems(
            options,
            checkedItems
        ) { _: DialogInterface?, which: Int, isChecked: Boolean ->
            if (userSelected.size >= 4 && isChecked) {
                Toasty.info(
                    requireContext(),
                    getString(R.string.app_shortcuts_max_toast),
                    Toast.LENGTH_SHORT,
                    true
                ).show()
                //((AlertDialog)dialog).getListView().setItemChecked(which, false); This doesn't work
            }
            if (isChecked) {
                userSelected.add(options[which].toString())
            } else {
                userSelected.remove(options[which].toString())
            }
        }

        builder.setNegativeButton(R.string.cancel, null)
        builder.setPositiveButton(R.string.select) { _: DialogInterface?, _: Int ->
            setAppShortcuts(
                remotes,
                userSelected
            )
        }

        builder.show()
    }

    private fun setAppShortcuts(
        remoteItems: ArrayList<RemoteItem>,
        appShortcuts: ArrayList<String>
    ) {
        var appShortcuts = appShortcuts
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        if (appShortcuts.size > 4) {
            appShortcuts = ArrayList(appShortcuts.subList(0, 4))
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(
            requireContext()
        )
        val editor = sharedPreferences.edit()
        val savedAppShortcutIds = sharedPreferences.getStringSet(
            getString(R.string.shared_preferences_app_shortcuts),
            HashSet()
        )
        val updatedAppShortcutIDds: MutableSet<String> = HashSet(savedAppShortcutIds)

        // Remove app shortcuts first
        val appShortcutIds = ArrayList<String>()
        for (s in appShortcuts) {
            appShortcutIds.add(AppShortcutsHelper.getUniqueIdFromString(s))
        }
        val removedIds: MutableList<String> = ArrayList(savedAppShortcutIds)
        removedIds.removeAll(appShortcutIds)
        if (removedIds.isNotEmpty()) {
            AppShortcutsHelper.removeAppShortcutIds(context, removedIds)
        }

        updatedAppShortcutIDds.removeAll(removedIds)

        // add new app shortcuts
        for (appShortcut in appShortcuts) {
            val id = AppShortcutsHelper.getUniqueIdFromString(appShortcut)
            if (updatedAppShortcutIDds.contains(id)) {
                continue
            }

            var remoteItem: RemoteItem? = null
            for (item in remoteItems) {
                if (item.name == appShortcut) {
                    remoteItem = item
                    break
                }
            }
            if (remoteItem == null) {
                continue
            }

            AppShortcutsHelper.addRemoteToAppShortcuts(context, remoteItem, id)
            updatedAppShortcutIDds.add(id)
        }

        editor.putStringSet(
            getString(R.string.shared_preferences_app_shortcuts),
            updatedAppShortcutIDds
        )
        editor.apply()
    }

}