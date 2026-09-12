package io.github.fnyoat.tipismine.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * hook 侧注册 Receiver：模块 App 发送 {@link #ACTION_RESTART_SYSTEMUI} 广播后，
 * hook 在 SystemUI 进程内接收，校验 token 后自杀（SystemUI 由 zygote 自动重启）。
 *
 * <p>鉴权：extra 携带模块配置中的 {@link Config#scanToken} 或 {@link Config#apiKey}，
 * hook 侧任一匹配即放行；两者均空时仅凭 action 即放行（攻击者只能触发 SystemUI 重启，无数据危害）。
 * 广播从模块 App 发出，属于同进程显式跨 UID 广播，receiver 需 {@code RECEIVER_EXPORTED}（API 33+）。
 */
public final class SystemUiRestarter {

    private static final String TAG = "TipIsMine";
    public static final String ACTION_RESTART_SYSTEMUI = "io.github.fnyoat.tipismine.RESTART_SYSTEMUI";
    public static final String EXTRA_TOKEN = "token";

    private static volatile boolean registered;

    private SystemUiRestarter() {
    }

    /**
     * 在 SystemUI 进程内注册 Receiver；幂等，多次调用只注册一次。
     *
     * @param ctx     Application context（hook 进程内，即 SystemUI 的 Context）
     * @param store   当前 config store，用于读取 scanToken / apiKey
     */
    public static void register(Context ctx, ConfigStore store) {
        if (registered || ctx == null) return;
        synchronized (SystemUiRestarter.class) {
            if (registered) return;
            try {
                IntentFilter f = new IntentFilter(ACTION_RESTART_SYSTEMUI);
                BroadcastReceiver br = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null || !ACTION_RESTART_SYSTEMUI.equals(intent.getAction())) {
                            return;
                        }
                        String token = intent.getStringExtra(EXTRA_TOKEN);
                        try {
                            Config cfg = store.load();
                            boolean authed = (!isEmpty(cfg.scanToken) && cfg.scanToken.equals(token))
                                    || (!isEmpty(cfg.apiKey) && cfg.apiKey.equals(token))
                                    || (isEmpty(cfg.scanToken) && isEmpty(cfg.apiKey));
                            if (authed) {
                                Log.i(TAG, "received restart SystemUI broadcast, killing process");
                                android.os.Process.killProcess(android.os.Process.myPid());
                            } else {
                                Log.w(TAG, "restart SystemUI broadcast rejected (token mismatch)");
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "restart SystemUI verification failed", t);
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(br, f, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(br, f);
                }
                registered = true;
                Log.i(TAG, "SystemUiRestarter registered");
            } catch (Throwable t) {
                Log.e(TAG, "SystemUiRestarter registration failed", t);
            }
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
