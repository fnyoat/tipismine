package io.github.fnyoat.tipismine.hook;

import android.app.Application;

/**
 * 在 SystemUI 进程内获取全局 {@link Application}。
 *
 * <p>{@code Resources.getContext()} 是 @hide API，编译期 adb 里不存在；
 * 这里用反射调用 {@code ActivityThread.currentApplication()}（同样 @hide，但可反射），
 * 两个 flavor（legacy / modern）共用。
 */
public final class AppContextProvider {

    private AppContextProvider() {
    }

    private static volatile Application cached;

    public static Application current() {
        Application app = cached;
        if (app != null) return app;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object c = at.getMethod("currentApplication").invoke(null);
            if (c instanceof Application) {
                cached = (Application) c;
                return (Application) c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}