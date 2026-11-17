package io.github.nitsuya.aa.display.util

interface AABroadcastConst {
    companion object {
        val ACTION_SCREEN_CONTROL = "aa.display.action.SCREEN_CONTROL"
        val ACTION_STEERING_WHEEL_CONTROL = "aa.display.action.STEERING_WHEEL_CONTROL"
        val EXTRA_ACTION = "aa.display.extra.ACTION"
        val EXTRA_TYPE = "aa.display.extra.TYPE"

        /** AaActivityService has displayed the projection screen (debounce basis for Coolwalk auto-open). */
        val ACTION_AA_DISPLAY_SHOWN = "aa.display.action.AA_DISPLAY_SHOWN"

        /** Coolwalk sidebar space has been reclaimed; projection can re-layout using the full head unit width. */
        val ACTION_COOLWALK_FULL_BLEED = "aa.display.action.COOLWALK_FULL_BLEED"
    }
}