package io.github.fnyoat.tipismine.hook;

/**
 * 槽位（owner / vpn）的“默认 / 显示 / 不显示”三态，纯逻辑、无 Android 依赖。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #DEFAULT}：显示与否完全跟随系统（系统显示就显示、不显示就不显示）；
 *       若配置了自定义文本，则只把内容替换成自定义文本，不改显隐。</li>
 *   <li>{@link #SHOW}：强制该槽位显示，并填入配置的自定义文本。</li>
 *   <li>{@link #HIDE}：强制该槽位不显示。</li>
 * </ul>
 */
public final class SlotMode {

    public static final String MODE_DEFAULT = "default";
    public static final String MODE_SHOW = "show";
    public static final String MODE_HIDE = "hide";

    public static final int DEFAULT = 0;
    public static final int SHOW = 1;
    public static final int HIDE = 2;

    private SlotMode() {
    }

    /** 配置字符串 → 三态；未知值按“默认”处理。 */
    public static int from(String raw) {
        if (MODE_SHOW.equals(raw)) return SHOW;
        if (MODE_HIDE.equals(raw)) return HIDE;
        return DEFAULT;
    }

    public static boolean isDefault(int mode) {
        return mode == DEFAULT;
    }

    public static boolean isShow(int mode) {
        return mode == SHOW;
    }

    public static boolean isHide(int mode) {
        return mode == HIDE;
    }
}