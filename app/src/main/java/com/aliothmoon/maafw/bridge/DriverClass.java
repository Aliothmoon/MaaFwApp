package com.aliothmoon.maafw.bridge;


import android.content.pm.PackageInfo;
import android.os.SystemClock;

import com.aliothmoon.maafw.remote.internal.ActivityUtils;
import com.aliothmoon.maafw.remote.internal.GameFpsMonitor;
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager;
import com.aliothmoon.maafw.third.FakeContext;
import com.aliothmoon.maafw.third.Ln;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import timber.log.Timber;

/**
 * upcall driver
 */
public final class DriverClass {

    private static final String TAG = "DriverClass";
    private static final int FRAME_WAIT_TIMEOUT_MS = 5000;
    private static final int FRAME_WAIT_INTERVAL_MS = 50;

    /** 无障碍写文本前拿它校验焦点输入框属于谁，免得写进别的应用 */
    private static volatile String targetPackage;

    /** 各 contact 按下的时刻（uptime ms），抬起时据此补足最短按住时长，见 {@link TapHoldPolicy} */
    private static final ConcurrentHashMap<Integer, Long> downAt = new ConcurrentHashMap<>();

    /** 按下后动过的 contact：滑动本就按得久，日志里与点击分开看 */
    private static final ConcurrentHashMap<Integer, Boolean> moved = new ConcurrentHashMap<>();

    private DriverClass() {
    }

    public static boolean startApp(String packageName, int displayId, boolean forceStop) {
        Ln.i(TAG + String.format(Locale.US, "%s %d %b", packageName, displayId, forceStop));
        logTargetAppInfo(packageName, displayId, forceStop);
        targetPackage = ActivityUtils.packageNameOf(packageName);
        if (displayId == PrimaryDisplayManager.DISPLAY_ID) {
            return ActivityUtils.startApp(packageName, displayId, forceStop);
        }
        String target = ActivityUtils.packageNameOf(packageName);
        boolean ret = ActivityUtils.startApp(packageName, displayId, forceStop, true);
        if (ret) {
            // 部分 ROM（如 One UI）会把游戏从虚拟屏挪回主屏，启动后校验并尝试拉回；
            // 拉不回则快速失败，避免识别对着虚拟屏空转
            // 这里比对的是包名，PI 给的可能是 "包名/Activity"，先拆
            ret = ActivityUtils.ensureAppOnDisplay(target, displayId);
            if (!ret) {
                Ln.e(TAG + ": " + target + " could not be pinned on display " + displayId);
            }
        }
        if (ret) {
            awaitFirstFrame();
            GameFpsMonitor.start(target);
        }
        return ret;
    }

    public static boolean inputText(byte[] utf8, int displayId) {
        return TextInputDispatcher.input(new String(utf8, StandardCharsets.UTF_8), displayId, targetPackage);
    }

    public static boolean stopApp(String packageName, int displayId) {
        Ln.i(TAG + String.format(Locale.US, ": stopApp %s displayId=%d", packageName, displayId));
        return ActivityUtils.forceStop(packageName, displayId);
    }

    private static void logTargetAppInfo(String rawSpec, int displayId, boolean forceStop) {
        String context = " displayId=" + displayId + " forceStop=" + forceStop;
        try {
            String target = ActivityUtils.packageNameOf(rawSpec);
            PackageInfo info = FakeContext.get().getPackageManager().getPackageInfo(target, 0);
            Ln.i(TAG + ": target app spec=" + rawSpec + " package=" + target
                    + " version=" + info.versionName + " (" + info.getLongVersionCode() + ")"
                    + " uid=" + (info.applicationInfo == null ? "unknown" : info.applicationInfo.uid)
                    + context);
        } catch (Throwable e) {
            Ln.w(TAG + ": target app info unavailable spec=" + rawSpec + context, e);
        }
    }

    private static void awaitFirstFrame() {
        long baseline = NativeBridgeLib.getFrameCount();
        int elapsed = 0;
        while (NativeBridgeLib.getFrameCount() <= baseline && elapsed < FRAME_WAIT_TIMEOUT_MS) {
            try {
                Thread.sleep(FRAME_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            elapsed += FRAME_WAIT_INTERVAL_MS;
        }
        if (elapsed >= FRAME_WAIT_TIMEOUT_MS) {
            Ln.w(TAG + ": awaitFirstFrame timed out after " + FRAME_WAIT_TIMEOUT_MS + "ms");
        }
    }

    /* 热路径：一次 Swipe / MultiSwipe 会连发几十次。坐标由框架记，这里不打日志 */

    public static boolean touchDown(int x, int y, int contact, int displayId) {
        boolean ok = InputControlUtils.down(x, y, contact, displayId);
        if (ok) {
            downAt.put(contact, SystemClock.uptimeMillis());
            moved.remove(contact);
        }
        return ok;
    }

    public static boolean touchMove(int x, int y, int contact, int displayId) {
        moved.put(contact, Boolean.TRUE);
        return InputControlUtils.move(x, y, contact, displayId);
    }

    /**
     * 抬起前补足最短按住时长：低帧率下按下与抬起落进同一帧间隙，游戏会整个吞掉这次点击
     *
     * 每次都记一行，用来实测按住时长、帧率与漏点的关系，参数定下来后再收
     */
    public static boolean touchUp(int x, int y, int contact, int displayId) {
        Long down = downAt.remove(contact);
        boolean swipe = moved.remove(contact) != null;
        if (down != null) {
            long held = SystemClock.uptimeMillis() - down;
            float fps = GameFpsMonitor.currentFps();
            long minHold = TapHoldPolicy.minHoldMs(fps);
            long pad = TapHoldPolicy.padMs(held, fps);
            if (pad > 0) {
                SystemClock.sleep(pad);
            }
            Ln.i(TAG + String.format(Locale.US,
                    ": touchUp %s contact=%d at=(%d,%d) held=%dms fps=%.1f minHold=%dms pad=%dms display=%d",
                    swipe ? "swipe" : "tap", contact, x, y, held, fps, minHold, pad, displayId));
        }
        return InputControlUtils.up(x, y, contact, displayId);
    }

    public static boolean keyDown(int keyCode, int displayId) {
        Ln.i(TAG + ": keyDown(keyCode=" + keyCode + ", displayId=" + displayId + ")");
        boolean result = InputControlUtils.keyDown(keyCode, displayId);
        Ln.i(TAG + ": keyDown result=" + result);
        return result;
    }

    public static boolean keyUp(int keyCode, int displayId) {
        Ln.i(TAG + ": keyUp(keyCode=" + keyCode + ", displayId=" + displayId + ")");
        boolean result = InputControlUtils.keyUp(keyCode, displayId);
        Ln.i(TAG + ": keyUp result=" + result);
        return result;
    }
}
