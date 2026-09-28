package com.aliothmoon.maafw.bridge;

/**
 * 点击的最短按住时长
 *
 * 游戏一般在每帧开头读一次触摸状态：按下与抬起落在同一帧间隙里，那一帧看到的就是「什么都没发生」。
 * 低帧率（10+ FPS，一帧 70~90ms）下 MaaFramework 的 ~50ms 点击会偶发被整个吞掉。
 * 低于 {@link #PAD_BELOW_FPS} 时至少跨 {@link #FRAMES} 帧，给帧时长抖动与事件分发留余量；
 * 帧率够高或取不到时不补等。{@link #CAP_MS} 远低于系统长按阈值（400~500ms），不会被当成长按
 *
 * 取值是按原理估的，尚未在具体游戏上实测，见 {@link DriverClass#touchUp} 里逐次点击的日志
 */
final class TapHoldPolicy {

    static final float PAD_BELOW_FPS = 30f;
    static final long CAP_MS = 250;
    static final int FRAMES = 2;

    private TapHoldPolicy() {
    }

    static long minHoldMs(float fps) {
        if (!(fps > 0f) || fps >= PAD_BELOW_FPS) {
            return 0L;
        }
        long frames = (long) Math.ceil(FRAMES * 1000.0 / fps);
        return Math.min(CAP_MS, frames);
    }

    /** 还需补等多久才能抬起；已按够时为 0 */
    static long padMs(long heldMs, float fps) {
        return Math.max(0L, minHoldMs(fps) - heldMs);
    }
}
