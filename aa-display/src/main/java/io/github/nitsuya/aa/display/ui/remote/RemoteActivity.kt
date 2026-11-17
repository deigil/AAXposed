package io.github.nitsuya.aa.display.ui.remote

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityRemoteBinding

/**
 * In-app remote control for the projected car virtual display.
 * Bypasses overlay / head-unit button limitations by invoking [CoreApi] directly.
 */
class RemoteActivity :
    BaseActivity<ActivityRemoteBinding>(ActivityRemoteBinding::class.java) {

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun initViews() {
        super.initViews()
        setTitle(R.string.remote_title)

        if (CoreApi.buildTime == 0L) {
            baseBinding.tvRemoteStatus.apply {
                text = getString(R.string.not_activated)
                visibility = View.VISIBLE
            }
        }

        baseBinding.btnBack.setOnClickListener { CoreApi.pressKey(KeyEvent.KEYCODE_BACK) }
        baseBinding.btnHome.setOnClickListener { CoreApi.startLauncher() }
        baseBinding.btnSwap.setOnClickListener { CoreApi.moveSecondTaskToFront() }
        baseBinding.btnPower.setOnClickListener { CoreApi.toggleDisplayPower() }

        baseBinding.btnMediaPrev.setOnClickListener { CoreApi.pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        baseBinding.btnMediaPlayPause.setOnClickListener { CoreApi.pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        baseBinding.btnMediaNext.setOnClickListener { CoreApi.pressKey(KeyEvent.KEYCODE_MEDIA_NEXT) }

        baseBinding.btnRefreshTasks.setOnClickListener { refreshRecentTasks() }
        baseBinding.btnAddShortcut.setOnClickListener { showAddShortcutDialog() }
    }

    override fun initData() {
        refreshRecentTasks()
        refreshShortcuts()
    }

    override fun onResume() {
        super.onResume()
        refreshRecentTasks()
    }

    private fun refreshRecentTasks() {
        val container = baseBinding.llRecentTasks
        container.removeAllViews()

        val recent = runCatching { CoreApi.getRecentTask() }.getOrNull()
        val tasks = recent?.virtualDisplay.orEmpty()
        if (tasks.isEmpty()) {
            baseBinding.tvNoRecentTasks.visibility = View.VISIBLE
            return
        }
        baseBinding.tvNoRecentTasks.visibility = View.GONE

        val density = resources.displayMetrics.density
        val iconSize = (36 * density).toInt()

        for (task in tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (12 * density).toInt()
                }
                if (task.logo != null) {
                    setImageBitmap(task.logo)
                }
            }

            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = task.label ?: "Task #${task.taskId}"
                textSize = 15f
            }

            val closeBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "✕"
                minWidth = 0
                minimumWidth = 0
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setOnClickListener {
                    CoreApi.removeTask(task.taskId)
                    row.postDelayed({ refreshRecentTasks() }, 250)
                }
            }

            row.setOnClickListener {
                CoreApi.moveTaskToFront(task.taskId)
            }

            row.addView(iconView)
            row.addView(titleView)
            row.addView(closeBtn)
            container.addView(row)
        }
    }

    private fun loadPinnedPackages(): MutableList<String> {
        val raw = prefs.getString(KEY_PINNED_APPS, null) ?: return mutableListOf()
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun savePinnedPackages(packages: List<String>) {
        prefs.edit().putString(KEY_PINNED_APPS, packages.joinToString("\n")).apply()
    }

    private fun refreshShortcuts() {
        val container = baseBinding.llShortcuts
        container.removeAllViews()

        val pinned = loadPinnedPackages()
        if (pinned.isEmpty()) {
            baseBinding.tvNoShortcuts.visibility = View.VISIBLE
            return
        }
        baseBinding.tvNoShortcuts.visibility = View.GONE

        val pm = packageManager
        val density = resources.displayMetrics.density
        val iconSize = (36 * density).toInt()

        for (pkg in pinned) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (12 * density).toInt()
                }
                setImageDrawable(runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
            }

            val label = runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            }.getOrDefault(pkg)

            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = label
                textSize = 15f
            }

            row.setOnClickListener {
                CoreApi.startActivity(pkg, 0)
                Toast.makeText(this, getString(R.string.remote_launching, label), Toast.LENGTH_SHORT).show()
            }

            row.setOnLongClickListener {
                val updated = loadPinnedPackages().filterNot { it == pkg }
                savePinnedPackages(updated)
                refreshShortcuts()
                true
            }

            row.addView(iconView)
            row.addView(titleView)
            container.addView(row)
        }
    }

    private fun showAddShortcutDialog() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launchIntent, 0)
        }

        val pinned = loadPinnedPackages().toSet()
        val candidates = infos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg in pinned || pkg == packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.ifBlank { null } ?: pkg
                pkg to "$label ($pkg)"
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.remote_no_apps_to_add, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = candidates.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_add_shortcut)
            .setItems(labels) { _, which ->
                val pkg = candidates[which].first
                val updated = loadPinnedPackages().apply { add(pkg) }
                savePinnedPackages(updated)
                refreshShortcuts()
            }
            .show()
    }

    private companion object {
        const val PREFS_NAME = "remote_shortcuts"
        const val KEY_PINNED_APPS = "pinned_packages"
    }
}

