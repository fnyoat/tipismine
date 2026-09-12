package io.github.fnyoat.tipismine.hook;

/**
 * 配置存储的抽象（跨进程）。两个 flavor 各有实现：
 * <ul>
 *   <li>legacy：{@code XSharedPreferences} + chmod 世界可读。</li>
 *   <li>modern：LSPosed 官方 {@code XRemotePreferences}（经 framework 通信，无 SELinux 问题）。</li>
 * </ul>
 */
public interface ConfigStore {

    /** 读取当前配置。实现需在返回前先 reload，保证读到最新写入。 */
    Config load();
}