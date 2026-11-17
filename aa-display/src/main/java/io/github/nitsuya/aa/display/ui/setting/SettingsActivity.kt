package io.github.nitsuya.aa.display.ui.setting

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivitySettingsBinding
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.GoogleMapsOnAaManager
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.util.WazeOnAaManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference

/**
 * Advanced settings: exposes keys in AADisplayConfig directly as preferences.
 *
 * Key point: the config file must remain readable by the Xposed side — PreferenceManager calls
 * getSharedPreferences internally, so we probe an available mode upfront and pass it in,
 * then call makeReadableForHooks after each change (falls back to relaxing file permissions
 * when MODE_WORLD_READABLE is unavailable).
 */
class SettingsActivity : BaseActivity<ActivitySettingsBinding>(ActivitySettingsBinding::class.java) {

    override fun initViews() {
        setTitle(R.string.more_settings_title)
        if (isFirstCreate) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fl_root, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // preferenceTheme is provided by Theme.AADisplay (PreferenceFragmentCompat resolves it
            // during onCreate, so calling applyStyle here would be too late).
            preferenceManager.apply {
                sharedPreferencesName = AADisplayConfig.ConfigName
                sharedPreferencesMode =
                    SharedPreferencesAccess.resolveModeForHooks(requireContext(), AADisplayConfig.ConfigName)
            }
            setPreferencesFromResource(R.xml.pref_aadisplay_config, rootKey)
            bindAppProjectionSwitches()
            bindPickers()
        }

        // ---------------------------------------------------------------
        // Choice-based settings: presets / installed options on device + "Custom..."
        // ---------------------------------------------------------------

        private data class Choice(val label: String, val value: String)

        private fun bindPickers() {
            val dpiPresets = listOf(
                Choice(getString(R.string.pref_dpi_auto), "0"),
                *intArrayOf(120, 160, 213, 240, 280, 320, 360, 420, 480)
                    .map { Choice(getString(R.string.pref_dpi_value, it), it.toString()) }
                    .toTypedArray()
            )
            bindChoicePreference(
                key = AADisplayConfig.VirtualDisplayDpi.key,
                presets = { dpiPresets },
                defaultValue = "0",
                customInputType = InputType.TYPE_CLASS_NUMBER,
                customCurrentLabel = { getString(R.string.pref_dpi_value_custom, it) }
            )
            bindChoicePreference(
                key = AADisplayConfig.AndroidAutoDpi.key,
                presets = { dpiPresets },
                defaultValue = "0",
                customInputType = InputType.TYPE_CLASS_NUMBER,
                customCurrentLabel = { getString(R.string.pref_dpi_value_custom, it) }
            )
            bindChoicePreference(
                key = AADisplayConfig.VoiceAssistShell.key,
                presets = {
                    listOf(Choice(getString(R.string.pref_voice_assist_default), "")) +
                        queryVoiceAssistants()
                },
                defaultValue = "",
                customInputType = InputType.TYPE_CLASS_TEXT,
                customCurrentLabel = { getString(R.string.pref_voice_assist_custom_current, it) }
            )
        }

        /**
         * ListPreference entries are built at runtime: presets + (current value if custom) + "Custom...".
         * Selecting "Custom..." pops up an input dialog and writes back to the same key so the format
         * read by the Xposed side stays unchanged.
         */
        private fun bindChoicePreference(
            key: String,
            presets: () -> List<Choice>,
            defaultValue: String,
            customInputType: Int,
            customCurrentLabel: (String) -> String
        ) {
            val preference = findPreference<ListPreference>(key) ?: return
            val presetList = presets()

            fun refresh() {
                val current = preference.value ?: defaultValue
                val choices = presetList.toMutableList()
                if (choices.none { it.value == current }) {
                    choices.add(Choice(customCurrentLabel(current), current))
                }
                choices.add(Choice(getString(R.string.pref_custom_entry), CUSTOM_VALUE))
                preference.entries = choices.map { it.label }.toTypedArray()
                preference.entryValues = choices.map { it.value }.toTypedArray()
                preference.summary = choices.first { it.value == current }.label
                // ListPreference highlights nothing when value is null
                if (preference.value == null) preference.setValueIndex(
                    choices.indexOfFirst { it.value == current }
                )
            }
            refresh()

            preference.setOnPreferenceChangeListener { _, newValue ->
                if (newValue != CUSTOM_VALUE) {
                    preference.value = newValue as String
                    refresh()
                    return@setOnPreferenceChangeListener false
                }
                val input = EditText(requireContext()).apply {
                    inputType = customInputType
                    setText(preference.value.orEmpty())
                    setSelection(text.length)
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(preference.title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        preference.value = input.text.toString().trim()
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            }
        }

        /**
         * Scan Activities on the phone declaring VOICE_COMMAND / ASSIST / VOICE_ASSIST and build an
         * `am start` command for each (single-quoted to avoid shell expansion of `$` in inner class names).
         */
        private fun queryVoiceAssistants(): List<Choice> {
            val pm = requireContext().packageManager
            val actions = listOf(
                Intent.ACTION_VOICE_COMMAND,
                Intent.ACTION_ASSIST,
                "android.intent.action.VOICE_ASSIST"
            )
            val found = linkedMapOf<String, Choice>()
            for (action in actions) {
                val intent = Intent(action)
                val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
                for (info in infos) {
                    val ai = info.activityInfo ?: continue
                    if (!ai.exported) continue
                    val component = "${ai.packageName}/${ai.name}"
                    val label = info.loadLabel(pm)?.toString()?.ifBlank { null } ?: ai.packageName
                    val command = "am start -a $action -n '$component'"
                    // Keep only the first action per component
                    found.putIfAbsent(
                        component,
                        Choice("$label (${ai.packageName})", command)
                    )
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }

        /**
         * "Disable Waze / Google Maps on AA" is not a pure config option: taking effect relies on
         * root `pm disable` on the projection component. Execute before persisting; on failure revert
         * the switch and return false so the preference stays in sync with the actual component state.
         */
        private fun bindAppProjectionSwitches() {
            bindShellBackedSwitch(AADisplayConfig.DisableWazeOnAa.key, R.string.disable_waze_on_aa_apply_failed) {
                WazeOnAaManager.apply(it)
            }
            bindShellBackedSwitch(
                AADisplayConfig.DisableGoogleMapsOnAa.key,
                R.string.disable_google_maps_on_aa_apply_failed
            ) {
                GoogleMapsOnAaManager.apply(it)
            }
        }

        private fun bindShellBackedSwitch(
            key: String,
            @StringRes failedMessage: Int,
            apply: (Boolean) -> Boolean
        ) {
            val preference = findPreference<Preference>(key) ?: return
            preference.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                if (apply(enabled)) {
                    true
                } else {
                    Toast.makeText(requireContext(), getString(failedMessage), Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        override fun onCreateRecyclerView(
            inflater: LayoutInflater,
            parent: ViewGroup,
            savedInstanceState: Bundle?
        ): RecyclerView {
            return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
                // Cards are drawn by MiuixCardDecoration, inset via RecyclerView horizontal padding
                val side = (16 * resources.displayMetrics.density).toInt()
                val bottom = (28 * resources.displayMetrics.density).toInt()
                setPadding(side, 0, side, bottom)
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                addItemDecoration(MiuixCardDecoration(requireContext()))
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // Disable the preference library's built-in divider; MiuixCardDecoration draws it instead
            setDivider(null)
            setDividerHeight(0)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            SharedPreferencesAccess.makeReadableForHooks(requireContext(), AADisplayConfig.ConfigName)
        }

        private companion object {
            const val CUSTOM_VALUE = "__custom__"
        }
    }
}
