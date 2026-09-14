package com.aliothmoon.maafw.bridge;


import android.content.pm.PackageInfo;

import com.aliothmoon.maafw.remote.internal.ActivityUtils;
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager;
import com.aliothmoon.maafw.third.FakeContext;
import com.aliothmoon.maafw.third.Ln;

import java.util.Locale;

import timber.log.Timber;

/**
 * upcall driver
 */
public final class DriverClass {

    private static final String TAG = "DriverClass";
    private static final int FRAME_WAIT_TIMEOUT_MS = 5000;
    private static final int FRAME_WAIT_INTERVAL_MS = 50;

    private DriverClass() {
    }

    public static boolean startApp(String packageName, int displayId, boolean forceStop) {
        Ln.i(TAG + String.format(Locale.US, "%s %d %b", packageName, displayId, forceStop));
        logTargetAppInfo(packageName, displayId, forceStop);
        if (displayId == PrimaryDisplayManager.DISPLAY_ID) {
            return ActivityUtils.startApp(packageName, displayId, forceStop);
        }
        boolean ret = ActivityUtils.startApp(packageName, displayId, forceStop, true);
        if (ret) {
            // 部分 ROM（如 One UI）会把游戏从虚拟屏挪回主屏，启动后校验并尝试拉回；
            // 拉不回则快速失败，避免识别对着虚拟屏空转
            // 这里比对的是包名，PI 给的可能是 "包名/Activity"，先拆
            String target = ActivityUtils.packageNameOf(packageName);
            ret = ActivityUtils.ensureAppOnDisplay(target, displayId);
            if (!ret) {
                Ln.e(TAG + ": " + target + " could not be pinned on display " + displayId);
            }
        }
        if (ret) {
            awaitFirstFrame();
        }
        return ret;
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
        return InputControlUtils.down(x, y, contact, displayId);
    }

    public static boolean touchMove(int x, int y, int contact, int displayId) {
        return InputControlUtils.move(x, y, contact, displayId);
    }

    public static boolean touchUp(int x, int y, int contact, int displayId) {
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
