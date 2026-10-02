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

    public static native Surface setupNativeCapturer(int width, int height);

    public static native void releaseNativeCapturer();

    @FastNative
    public static native void setPreviewSurface(Object surface);

    /** 停渲染线程并断开预览 Surface，会阻塞到线程退出；进程退出前调，否则这块 Surface 下一个特权进程接不上 */
    public static native void shutdownPreview();

    /**
     * 测试用
     */
    public static native Bitmap getFrameBufferBitmap();

    @FastNative
    public static native long getFrameCount();

    /**
     * 把帧缓冲换成黑帧并把预览画黑，换了才返回 true；已经是黑帧、或 expectedFrameCount 之后又来过新帧时不动
     * 不计入 {@link #getFrameCount()}
     */
    public static native boolean blankFrame(long expectedFrameCount);

}
