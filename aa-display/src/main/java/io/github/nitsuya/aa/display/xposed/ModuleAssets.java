/*
 * AADisplay - EzXHelper module-path bootstrap for the modern Xposed API.
 */

package io.github.nitsuya.aa.display.xposed;

import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit;
import com.github.kyuubiran.ezxhelper.init.InitFields;

/**
 * Populates EzXHelper's {@code modulePath} / {@code moduleRes}.
 *
 * <p>The legacy path ({@link XposedInit}) populates these two fields via
 * {@code EzXHelperInit.initZygote(startupParam)}, the only public setter. Under the modern API
 * there is no {@code StartupParam} (package-private constructor) and {@code XposedInterface} provides
 * no modulePath accessor, so {@link ModernInit} injects a path supplier
 * ({@code getModuleApplicationInfo().sourceDir}) resolved here **only when actually needed**.
 *
 * <p>Failing to populate them is fatal rather than a missing feature: {@code InitFields.modulePath}
 * is lateinit; {@code addModuleAssetPath} called during {@code CommonContextWrapper} construction
 * reads it and throws {@code UninitializedPropertyAccessException}. Since that construction happens
 * on system_server's main looper (when CoreManagerService creates the floating window), an uncaught
 * exception triggers a {@code je#system_server} soft reboot.
 *
 * <p>Path resolution is intentionally lazy: {@code getModuleApplicationInfo()} is a synchronous Binder
 * call, and {@code onModuleLoaded} in system_server runs before {@code SystemServer.startBootstrapServices},
 * when we shouldn't hit the framework daemon. If the supplier fails, {@link #apkPathFromClassLoader()}
 * serves as fallback — as long as hook code reaches this line, the module APK is in its ClassLoader.
 *
 * <p>Written in Java because the two setters are {@code internal} in Kotlin (invisible across
 * compilation units) yet public in bytecode ({@code setModulePath$EzXHelper_release} /
 * {@code setModuleRes$EzXHelper_release}), so javac can call them directly — safer than reflection
 * because R8 renames call sites together during obfuscation.
 */
public final class ModuleAssets {

    private static final String TAG = "AAD_ModuleAssets";

    /** Source of the module APK path. Injected by {@link ModernInit} on the modern path. */
    public interface PathSupplier {
        @Nullable
        String getModuleApkPath();
    }

    @Nullable
    private static volatile PathSupplier sPathSupplier;

    /** Do not keep retrying after failure (every retry is a wasted IPC + log noise). */
    private static volatile boolean sResolveFailed;

    private ModuleAssets() {
    }

    /** Called by the entry class before dispatching any hooks. */
    public static void installPathSupplier(@NonNull PathSupplier supplier) {
        sPathSupplier = supplier;
        sResolveFailed = false;
    }

    /**
     * Idempotently initializes {@code modulePath} (and best-effort {@code moduleRes}).
     * Short-circuits on the legacy path where {@code initZygote} already set them.
     *
     * @return whether modulePath is available
     */
    public static boolean ensureModulePath() {
        if (InitFields.INSTANCE.isModulePathInited()) {
            return true;
        }
        if (sResolveFailed) {
            return false;
        }
        return resolve();
    }

    private static synchronized boolean resolve() {
        InitFields fields = InitFields.INSTANCE;
        if (fields.isModulePathInited()) {
            return true;
        }
        PathSupplier supplier = sPathSupplier;
        String apkPath = null;
        if (supplier == null) {
            // No supplier injected (e.g. a hooked process loaded only via legacy channel,
            // or framework changed dispatch). Non-fatal: ClassLoader fallback is below.
            Log.w(TAG, "no module path supplier installed; falling back to class loader");
        } else {
            try {
                apkPath = supplier.getModuleApkPath();
            } catch (Throwable t) {
                Log.e(TAG, "module path supplier failed", t);
            }
        }
        if (apkPath == null || apkPath.isEmpty()) {
            apkPath = apkPathFromClassLoader();
        }
        if (apkPath == null || apkPath.isEmpty()) {
            Log.w(TAG, "module apk path unavailable; module resources stay unusable");
            sResolveFailed = true;
            return false;
        }
        try {
            fields.setModulePath$EzXHelper_release(apkPath);
        } catch (Throwable t) {
            Log.e(TAG, "failed to set modulePath=" + apkPath, t);
            sResolveFailed = true;
            return false;
        }
        // moduleRes has no direct callers currently, but other EzXHelper APIs read it; populate it too.
        // XModuleResources is part of the legacy Xposed API and unblocked under targetApiVersion=101;
        // failure only affects resource forwarding and should not fail an already-resolved modulePath.
        try {
            if (!fields.isModuleResInited()) {
                fields.setModuleRes$EzXHelper_release(XModuleResources.createInstance(apkPath, null));
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to create module resources for " + apkPath, t);
        }
        Log.i(TAG, "modulePath initialized: " + apkPath);
        return true;
    }

    /**
     * Fallback: extract the APK path from the {@code PathClassLoader} that loaded this class.
     *
     * <p>Module code is always loaded from the module APK, so {@code BaseDexClassLoader.toString()}
     * always contains its dex path in the format (AOSP {@code BaseDexClassLoader#toString}):
     * {@code dalvik.system.PathClassLoader[DexPathList[[zip file "/data/app/.../base.apk"], ...]]}.
     * Pure text parsing avoids hidden APIs and reflection whitelists, making it more reliable than
     * {@code DexPathList.dexElements}. Taking the first {@code .apk} suffices since only base.apk holds resources.
     */
    @Nullable
    private static String apkPathFromClassLoader() {
        ClassLoader loader = ModuleAssets.class.getClassLoader();
        if (loader == null) {
            return null;
        }
        String description = loader.toString();
        int end = description.indexOf(".apk\"");
        if (end < 0) {
            Log.w(TAG, "no apk path in class loader: " + description);
            return null;
        }
        int start = description.lastIndexOf('"', end);
        if (start < 0) {
            return null;
        }
        String path = description.substring(start + 1, end + 4);
        Log.i(TAG, "module apk path from class loader: " + path);
        return path;
    }

    /**
     * Safe wrapper around {@code EzXHelperInit.addModuleAssetPath}: returns false if path resolution
     * or reflection fails, never throwing to callers (call sites include system_server main thread).
     */
    public static boolean addModuleAssetPath(@NonNull Resources resources) {
        if (!ensureModulePath()) {
            Log.w(TAG, "modulePath unavailable; module assets not added to " + resources);
            return false;
        }
        try {
            EzXHelperInit.INSTANCE.addModuleAssetPath(resources);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "addModuleAssetPath failed", t);
            return false;
        }
    }
}
