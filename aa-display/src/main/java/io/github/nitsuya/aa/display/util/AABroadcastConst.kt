package io.github.nitsuya.aa.display.util

interface AABroadcastConst {
    companion object {
        val ACTION_SCREEN_CONTROL = "aa.display.action.SCREEN_CONTROL"
        val ACTION_STEERING_WHEEL_CONTROL = "aa.display.action.STEERING_WHEEL_CONTROL"
        val EXTRA_ACTION = "aa.display.extra.ACTION"
        val EXTRA_TYPE = "aa.display.extra.TYPE"

        /** AaActivityService 已把投屏画面显示出来（Coolwalk 自动打开的去抖依据）。 */
        val ACTION_AA_DISPLAY_SHOWN = "aa.display.action.AA_DISPLAY_SHOWN"

        /** Coolwalk 侧边栏空间已回收，投屏可按整块车机宽度重新布局。 */
        val ACTION_COOLWALK_FULL_BLEED = "aa.display.action.COOLWALK_FULL_BLEED"
    }
}