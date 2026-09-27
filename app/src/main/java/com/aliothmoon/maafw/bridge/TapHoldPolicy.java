package com.aliothmoon.maafw.bridge;

/**
 * 点击的最短按住时长
 *
 * 游戏一般在每帧开头读一次触摸状态：按下与抬起落在同一帧间隙里，那一帧看到的就是「什么都没发生」。
 * 低帧率（10+ FPS，一帧 70~90ms）下 MaaFramework 的 ~50ms 点击会偶发被整个吞掉。
 * 至少跨 {@link #FRAMES} 帧给帧时长抖动与事件分发留余量；{@link #FLOOR_MS} 取 Android 轻点的量级，
 * {@link #CAP_MS} 远低于系统长按阈值（400~500ms），不会被当成长按
 *
 * 取值是按原理估的，尚未在具体游戏上实测，见 {@link DriverClass#touchUp} 里逐次点击的日志
 */
final class TapHoldPolicy {

    static final long FLOOR_MS = 100;
    static final long CAP_MS = 250;
    static final int FRAMES = 2;

    private TapHoldPolicy() {
    }

    /** fps 未知（&lt;= 0）时只用下限 */
    static long minHoldMs(float fps) {
        if (!(fps > 0f)) {
            return FLOOR_MS;
        }
        long frames = (long) Math.ceil(FRAMES * 1000.0 / fps);
        return Math.max(FLOOR_MS, Math.min(CAP_MS, frames));
    }

    /** 还需补等多久才能抬起；已按够或是滑动时为 0 */
    static long padMs(long heldMs, float fps) {
        return Math.max(0L, minHoldMs(fps) - heldMs);
    }
}
