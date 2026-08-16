package com.aliothmoon.maafw.bridge;

import android.graphics.Bitmap;
import android.view.Surface;

import com.aliothmoon.maafw.third.Ln;

import dalvik.annotation.optimization.FastNative;

public class NativeBridgeLib {
    public static boolean LOADED;

    static {
        try {
            System.loadLibrary("bridge");
            LOADED = true;
        } catch (Throwable e) {
            LOADED = false;
            Ln.e("NativeBridgeLib static initializer: ", e);
        }
    }

    // for test
    @FastNative
    public static native String ping();

    // fw 版本闸，见 MaaFwVersion；false 时触摸恒按单指 contact 0 注入
    public static native void setContactSupport(boolean supported);

    public static native Surface setupNativeCapturer(int width, int height);

    public static native void releaseNativeCapturer();

    @FastNative
    public static native void setPreviewSurface(Object surface);

    /**
     * 测试用
     */
    public static native Bitmap getFrameBufferBitmap();

    @FastNative
    public static native long getFrameCount();

}
