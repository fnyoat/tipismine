package io.github.fnyoat.tipismine.hook;

import android.content.Context;

import java.util.Map;

/**
 * 配置 UI 用的读写访问抽象（双 flavor 各有实现）。
 */
public interface ConfigAccess {

    /** 读取当前生效配置（内存模型）。 */
    Config load();

    /** 写多项配置并落盘；实现负责处理跨进程可见性（chmod / 远程 prefs）。 */
    void write(Context ctx, Map<String, Object> values);

    /** 模块是否已在框架中激活（用于横幅：绿 / 红）。语义见各实现。 */
    boolean isActive();
}