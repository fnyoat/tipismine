package io.github.fnyoat.tipismine.hook;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * legacy 风味入口（老 Xposed / 老版 LSPosed / Android 7 兼容）。
 *
 * <p>作用域：com.android.systemui。核心能力：
 * <ol>
 *   <li>owner / vpn 各自“默认 / 显示 / 不显示”三态。</li>
 *   <li>整段替换提示文案。</li>
 * </ol>
 * 全部通过改写 SystemUI 的 {@code Resources.getString} 字符串结果实现，不碰底层 owner 判定。
 */
public class TipIsMine implements IXposedHookLoadPackage {

    public static final String PACKAGE_NAME = "io.github.fnyoat.tipismine";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String TAG = "TipIsMine";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": hooking SystemUI");
        try {
            new SystemUiHooks(lpparam.classLoader, new LegacyConfig()).install();
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }
}