package io.github.fnyoat.tipismine.hook;

import android.content.Context;
import android.content.Intent;

/**
 * 内容扫描命中回传：hook 侧（SystemUI 进程）检测到"不在内置/自定义名单但模板命中"的
 * 新资源名时，把资源名显式广播回模块 App；模块 App 的调试页收到后自动填入自定义名单。
 *
 * <p>hook 侧无法写远程 prefs（对 hooked app 只读），故用显式广播跨进程投递。
 * 用配置里的 scan_token 认证，避免其它应用伪造广播注入名单。
 */
public final class ScanReporter {

    public static final String ACTION_SCAN_HIT = "io.github.fnyoat.tipismine.SCAN_HIT";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_TOKEN = "token";

    private ScanReporter() {
    }

    /** hook 侧调用：把检测到的资源名回传给模块 App。任何异常都不抛出（不打断正常 hook 链）。 */
    public static void report(Context ctx, Config cfg, String resourceName) {
        if (ctx == null || cfg == null || resourceName == null || resourceName.isEmpty()) {
            return;
        }
        if (cfg.scanToken == null || cfg.scanToken.isEmpty()) {
            return;
        }
        try {
            Intent i = new Intent(ACTION_SCAN_HIT)
                    .setPackage("io.github.fnyoat.tipismine")
                    .putExtra(EXTRA_NAME, resourceName)
                    .putExtra(EXTRA_TOKEN, cfg.scanToken);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}