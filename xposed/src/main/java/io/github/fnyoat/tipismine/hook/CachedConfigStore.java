package io.github.fnyoat.tipismine.hook;

/**
 * 带 TTL 的 {@link ConfigStore} 缓存包装：把"每次 getString(path 主线程最热路径) 都跨进程/落盘读配置"
 * 收敛为"每 TTL 最多一次真实读取，其余命中内存快照"。
 *
 * <p>仅在 hook 侧（SystemUI 进程）使用——文案改写容忍约 1 秒的生效延迟，换取高频调用
 * 从同步 I/O 降为纯内存读，避免 SystemUI 主线程卡顿。UI 侧仍走实时 {@link ConfigAccess}。
 *
 * <p>读失败时回退到上一次成功的快照（宁可短暂延迟生效，也不在热路径上抛异常）。
 */
public final class CachedConfigStore implements ConfigStore {

    private final ConfigStore delegate;
    private final long ttlMs;

    private volatile Config cached;
    private volatile long fetchedAt;

    public CachedConfigStore(ConfigStore delegate) {
        this(delegate, 1000L);
    }

    public CachedConfigStore(ConfigStore delegate, long ttlMs) {
        this.delegate = delegate;
        this.ttlMs = ttlMs <= 0 ? 1000L : ttlMs;
    }

    @Override
    public Config load() {
        Config c = cached;
        long now = System.currentTimeMillis();
        if (c != null && now - fetchedAt < ttlMs) {
            return c;
        }
        try {
            c = delegate.load();
        } catch (Throwable t) {
            if (cached != null) {
                return cached; // 读失败：沿用旧值
            }
            return null;
        }
        if (c != null) {
            cached = c;
            fetchedAt = now;
        }
        return c;
    }
}