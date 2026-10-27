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
 * 高级设置：把 AADisplayConfig 里的键位直接暴露成偏好项。
 *
 * 关键点是配置文件必须仍然能被 Xposed 侧读到 —— PreferenceManager 自己去
 * getSharedPreferences，所以这里提前探测出可用的 mode 交给它，并在每次改动后
 * 补一次 makeReadableForHooks（MODE_WORLD_READABLE 不可用时会退化成放宽文件权限）。
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
            // preferenceTheme 由 Theme.AADisplay 提供（PreferenceFragmentCompat 在
            // onCreate 阶段就解析完了，这里再 applyStyle 已经来不及）
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
        // 选择式设置：预设 / 手机上已安装的选项 + 「自定义…」
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
         * ListPreference 的 entries 在运行时生成：预设项 + （当前值不在预设里时的「自定义：…」）+ 「自定义…」。
         * 选中「自定义…」会弹出文本框，保存后写回同一个 key，所以 Xposed 侧读取的格式不变。
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
                // value 为 null 时 ListPreference 不会高亮任何项
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
         * 扫描手机上声明了 VOICE_COMMAND / ASSIST / VOICE_ASSIST 的 Activity，
         * 每个生成一条 `am start` 命令（单引号包起来，避免内部类名里的 `$` 被 shell 展开）。
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
                    // 同一个组件只保留第一个 action
                    found.putIfAbsent(
                        component,
                        Choice("$label (${ai.packageName})", command)
                    )
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }

        /**
         * 「禁止 Waze / Google 地图投屏」不是纯配置项：真正生效靠 root 去
         * pm disable 对应的投屏组件。这里在写入前先执行，失败就把开关退回原位、
         * 返回 false 让 preference 不落盘，免得配置和实际组件状态脱节。
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
                // 卡片由 MiuixCardDecoration 画，靠 RecyclerView 的左右 padding 内缩
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
            // 关掉 preference 库自带的分割线，改由 MiuixCardDecoration 绘制
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
