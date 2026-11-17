package io.github.nitsuya.aa.display.xposed.hook

import de.robv.android.xposed.XC_MethodHook

abstract class BaseHook {
    var isInit: Boolean = false
    abstract val tagName: String
    /** Called by the entry class (ModernInit for modern / XposedInit for legacy) after translating the target. */
    abstract fun init(target: HookTarget)
}
inline fun XC_MethodHook.MethodHookParam.abortMethod() {
    this.result = null
}