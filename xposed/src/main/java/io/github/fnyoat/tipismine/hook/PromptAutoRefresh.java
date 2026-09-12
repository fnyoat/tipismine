package io.github.fnyoat.tipismine.hook;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让 SystemUI 的归属提示按 {@link Config#KEY_REFRESH_INTERVAL} 自动刷新。
 *
 * <p>原理：
 * <ol>
 *   <li>getString hook 命中归属提示时，{@link #registerOutput} 记住输出文本与重建参数；</li>
 *   <li>{@code TextView.setText} hook 发现传入的文本与该输出一致时，{@link #onTextViewSetText}
 *       把该 TextView 记入弱引用表；</li>
 *   <li>起系统主线程定时任务（按刷新间隔）：仅对**可视**（{@code isShown()}）的 TextView
 *       重新跑 {@link PromptRewriter}（用最新配置与当前设备状态 + ${get}/${post}），
 *       若结果变了就重写文案。不可视的 TextView 保持引用但不刷写。</li>
 * </ol>
 *
 * <p>目标进程仅 SystemUI。无命中时 setText 只做一次字符串比对，不做任何事；
 * 没有记下的 TextView 时定时任务自动停摆；提示不可视时连设备状态 / HTTP 预取都会被暂停。
 */
public final class PromptAutoRefresh {

    private static final int RECENT_LIMIT = 5;

    private final ConfigStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick;

    /** 最近输出的文本 → 重建参数（避免长时间持有全部输出）。 */
    private final ConcurrentHashMap<String, Task> taskByOutput = new ConcurrentHashMap<>();
    /** 最近输出顺序（FIFO，淘汰最旧）。 */
    private final ArrayList<String> recentOrder = new ArrayList<>();
    /** 已捕获的 TextView（弱引用，消失自动清理）。 */
    private final WeakHashMap<TextView, String> outputByView = new WeakHashMap<>();

    private SystemUiExpressionContext exprCtx;

    /** 本机存在的内置 key 条目（与 getString hook 共享；null 时回退全量名单）。 */
    private volatile String[][] activeEntries;

    public void setActiveEntries(String[][] entries) {
        if (entries != null) this.activeEntries = entries;
    }

    public PromptAutoRefresh(ConfigStore store) {
        this.store = store;
        this.tick = new Runnable() {
            @Override
            public void run() {
                refresh();
                reschedule();
            }
        };
        reschedule();
    }

    /**
     * 复用 hook 垫片创建的表达式上下文（与即时 getString 改写共享同一套 HTTP 预取缓存）。
     * 垫片创建后调用；本类<strong>不会</strong>再自建第二份。
     */
    public void setExpressionContext(SystemUiExpressionContext ctx) {
        if (ctx != null) this.exprCtx = ctx;
    }

    /* ------------------------------------------------------------------ */
    /* 供 hook 垫片调用                                                     */
    /* ------------------------------------------------------------------ */

    /** getString 改写输出时登记（可在任意线程调用）。动态与否由配置的<b>源码</b>文本判定：
     *  表达式求值发生在组装之后，最终输出可能已不含 {@code ${...}}，不能拿输出判动态。 */
    public void registerOutput(String output, String resPackageName, String resourceName,
                               Object[] args, String template, Config config) {
        if (output == null || output.isEmpty()) return;
        boolean dynamic = PromptRewriter.sourceIsDynamic(config);
        synchronized (recentOrder) {
            taskByOutput.put(output, new Task(resPackageName, resourceName, snapshot(args), template, dynamic));
            recentOrder.add(output);
            if (recentOrder.size() > RECENT_LIMIT) {
                taskByOutput.remove(recentOrder.remove(0));
            }
        }
    }

    /** setText 传入文本命中我们的输出时，捕获该 TextView（主线程调用）。
     *  仅<b>动态</b>输出（含 {@code ${...}}）才进入定时刷新跟踪，静态文本一次渲染即止，
     *  tick 完全不启动。 */
    public void onTextViewSetText(TextView tv, CharSequence text) {
        if (tv == null || text == null) return;
        String s = text.toString();
        Task task = taskByOutput.get(s);
        if (task == null) return;
        forceMultiline(tv); // 让归属提示按多行渲染（换行符 \n 生效）
        if (task.dynamicResource) {
            outputByView.put(tv, s);
            reschedule();
        }
    }

    /** 强制该 TextView 以多行方式渲染：撤销单行/省略号限制，使 \n 能换行显示。 */
    private void forceMultiline(TextView tv) {
        try {
            tv.setSingleLine(false);
            tv.setMaxLines(Integer.MAX_VALUE);
            tv.setEllipsize(null);
            tv.setHorizontallyScrolling(false);
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* 定时刷新                                                             */
    /* ------------------------------------------------------------------ */

    private void reschedule() {
        if (outputByView.isEmpty()) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, intervalMs());
    }

    private long intervalMs() {
        float sec = Config.KEY_DEFAULT_REFRESH_INTERVAL;
        try {
            sec = store.load().refreshInterval;
        } catch (Throwable ignored) {
        }
        if (sec < 0.1f) sec = 0.1f;
        return (long) (sec * 1000);
    }

    private void refresh() {
        boolean anyVisible = false;

        for (Map.Entry<TextView, String> e : new ArrayList<>(outputByView.entrySet())) {
            TextView tv = e.getKey();
            if (tv == null) {
                outputByView.remove(tv);
                continue;
            }
            if (!tv.isShown()) {
                continue; // 不可视：保持引用，等下次可见再刷
            }
            anyVisible = true;
            if (exprCtx == null) {
                continue; // 上下文未就绪（垫片尚未创建），下次再刷
            }

            Task task = taskByOutput.get(e.getValue());
            if (task == null) {
                outputByView.remove(tv);
                continue;
            }
            // 输出不再动态（如用户改为纯静态文案）→ 移除跟踪，令 tick 停摆。
            if (!task.dynamicResource) {
                outputByView.remove(tv);
                continue;
            }
            Config config;
            try {
                config = store.load();
            } catch (Throwable t) {
                continue;
            }
            String out = PromptRewriter.maybeRewrite(config,
                    task.resPackageName, task.resourceName, task.args, task.template, exprCtx,
                    activeEntries);
            if (out == null) {
                outputByView.remove(tv); // 用户已全局不干预 → 停止跟踪
                continue;
            }
            registerOutput(out, task.resPackageName, task.resourceName, task.args, task.template, config);
            if (!out.equals(tv.getText().toString())) {
                tv.setText(out);
                outputByView.put(tv, out);
            }
            // 配置源码不含 ${...}（纯静态文案）→ 无需再定时重算。
            if (!PromptRewriter.sourceIsDynamic(config)) {
                outputByView.remove(tv);
                continue;
            }
        }

        /* 按可见性控制 get/post 预取（仅在至少一个提示可视时才抓取）。 */
        if (exprCtx != null) {
            exprCtx.setPrefetchTtlMs(Math.max(1, intervalMs()));
            exprCtx.setPaused(!anyVisible);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                 */
    /* ------------------------------------------------------------------ */

    private static Object[] snapshot(Object[] args) {
        if (args == null) return null;
        Object[] copy = new Object[args.length];
        System.arraycopy(args, 0, copy, 0, args.length);
        return copy;
    }

    /** 重建归属提示所需的全部信息。 */
    private static final class Task {
        final String resPackageName;
        final String resourceName;
        final Object[] args;
        final String template;
        /** 输出是否含动态表达式（含 {@code ${...}} 才需要定时重算）。 */
        final boolean dynamicResource;

        Task(String resPackageName, String resourceName, Object[] args, String template,
             boolean dynamicResource) {
            this.resPackageName = resPackageName;
            this.resourceName = resourceName;
            this.args = args;
            this.template = template;
            this.dynamicResource = dynamicResource;
        }
    }
}