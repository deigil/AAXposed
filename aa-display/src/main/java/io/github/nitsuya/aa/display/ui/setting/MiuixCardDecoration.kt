package io.github.nitsuya.aa.display.ui.setting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.nitsuya.aa.display.R

/**
 * Draws the preference list as miuix-style grouped cards:
 * consecutive regular preference rows merge into a single large rounded white card,
 * category headers (tag = miuix_category) stay outside the card, and a thin left-indented
 * divider is drawn between rows.
 *
 * Cards form the background layer so they are drawn in onDraw (before child views);
 * horizontal inset is provided by RecyclerView's own padding (see SettingsActivity).
 */
class MiuixCardDecoration(context: Context) : RecyclerView.ItemDecoration() {

    companion object {
        private const val CATEGORY_TAG = "miuix_category"
    }

    private val density = context.resources.displayMetrics.density
    private val radius = 18f * density
    private val dividerHeight = (0.5f * density).coerceAtLeast(1f)
    private val dividerInset = 18f * density

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.miuix_card)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.miuix_divider)
    }
    private val rect = RectF()

    private fun isCategory(parent: RecyclerView, child: View): Boolean {
        // Tag only applies to the custom category layout; the library may use other layouts,
        // so check Preference type via adapter first and fall back to tag.
        val adapter = parent.adapter
        if (adapter is PreferenceGroupAdapter) {
            val position = parent.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION) {
                return adapter.getItem(position) is PreferenceCategory
            }
        }
        return child.tag == CATEGORY_TAG
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        if (right <= left) return

        var groupStart: View? = null
        var groupEnd: View? = null

        fun flush() {
            val start = groupStart ?: return
            val end = groupEnd ?: start
            rect.set(left, start.top.toFloat(), right, end.bottom.toFloat())
            canvas.drawRoundRect(rect, radius, radius, cardPaint)
            groupStart = null
            groupEnd = null
        }

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (isCategory(parent, child)) {
                flush()
                continue
            }
            if (groupStart == null) {
                groupStart = child
            } else {
                // Draw divider between adjacent rows within a group
                canvas.drawRect(
                    left + dividerInset,
                    child.top.toFloat(),
                    right,
                    child.top + dividerHeight,
                    dividerPaint
                )
            }
            groupEnd = child
        }
        flush()
    }
}
