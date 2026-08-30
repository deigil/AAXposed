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
 * 把偏好列表画成 miuix 的“分组卡片”：
 * 连续的普通偏好行合并成一张大圆角白卡片，分组标题（tag = miuix_category）
 * 留在卡片之外，行与行之间画一条左缩进的细分割线。
 *
 * 卡片是背景层，所以在 onDraw（子 View 之前）绘制；水平内缩由 RecyclerView
 * 自身的 padding 提供（见 SettingsActivity）。
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
        // tag 只在自定义 category 布局生效；库内部还可能用别的布局，
        // 所以优先按 adapter 里的 Preference 类型判断，tag 作为兜底。
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
                // 组内相邻两行之间画分割线
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
