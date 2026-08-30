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
    }
}
