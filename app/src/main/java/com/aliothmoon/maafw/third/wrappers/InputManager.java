package com.aliothmoon.maafw.third.wrappers;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.aliothmoon.maafw.constant.AndroidVersions;
import com.aliothmoon.maafw.third.DisplayInfo;
import com.aliothmoon.maafw.third.FakeContext;
import com.aliothmoon.maafw.third.Ln;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class InputManager {

    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 5000;

    private final android.hardware.input.InputManager manager;
    private long lastPermissionLogDate;
    private static long lastInjectionDiagnosticLogDate;

    private static Method injectInputEventMethod;
    private static Method setDisplayIdMethod;
    private static Method getDisplayIdMethod;
    private static Method setActionButtonMethod;
    private static Method addUniqueIdAssociationByPortMethod;
    private static Method removeUniqueIdAssociationByPortMethod;

    static InputManager create() {
        android.hardware.input.InputManager manager = (android.hardware.input.InputManager) FakeContext.get()
                .getSystemService(FakeContext.INPUT_SERVICE);
        return new InputManager(manager);
    }

    private InputManager(android.hardware.input.InputManager manager) {
        this.manager = manager;
    }

    private static Method getInjectInputEventMethod() throws NoSuchMethodException {
        if (injectInputEventMethod == null) {
            injectInputEventMethod = android.hardware.input.InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        return injectInputEventMethod;
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        long startNanos = SystemClock.elapsedRealtimeNanos();
        try {
            Method method = getInjectInputEventMethod();
            boolean injected = (boolean) method.invoke(manager, inputEvent, mode);
            if (!injected) {
                Ln.w("injectInputEvent returned false"
                        + " event=" + describeInputEvent(inputEvent)
                        + " displayId=" + getDisplayIdForLog(inputEvent)
                        + " mode=" + mode
                        + " elapsedMs=" + (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
                        + injectionDiagnostics("RETURNED_FALSE", getDisplayIdForDiagnostics(inputEvent)));
            }
            return injected;
        } catch (ReflectiveOperationException e) {
            if (e instanceof InvocationTargetException) {
                Throwable cause = e.getCause();
                if (cause instanceof SecurityException) {
                    String message = e.getCause().getMessage();
                    if (message != null && message.contains("INJECT_EVENTS permission")) {
                        // Do not flood the console, limit to one permission error log every 3 seconds
                        long now = System.currentTimeMillis();
                        if (lastPermissionLogDate <= now - 3000) {
                            Ln.e(message);
                            Ln.e("Make sure you have enabled \"USB debugging (Security Settings)\""
                                    + " and then rebooted your device.");
                            lastPermissionLogDate = now;
                        }
                        Ln.w("injectInputEvent invocation rejected"
                                + " event=" + describeInputEvent(inputEvent)
                                + " displayId=" + getDisplayIdForLog(inputEvent)
                                + " mode=" + mode
                                + " cause=" + cause.getClass().getName()
                                + ":" + cause.getMessage()
                                + " elapsedMs="
                                + (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
                                + injectionDiagnostics(
                                        "SECURITY_REJECTED",
                                        getDisplayIdForDiagnostics(inputEvent)));
                        // Do not print the stack trace
                        return false;
                    }
                }
            }
            Ln.e("Could not invoke injectInputEvent"
                            + " event=" + describeInputEvent(inputEvent)
                            + " displayId=" + getDisplayIdForLog(inputEvent)
                            + " mode=" + mode,
                    e);
            return false;
        }
    }

    private static String describeInputEvent(InputEvent inputEvent) {
        if (inputEvent instanceof MotionEvent) {
            MotionEvent event = (MotionEvent) inputEvent;
            return "MotionEvent(action=" + event.getActionMasked()
                    + ",actionRaw=" + event.getAction()
                    + ",pointerCount=" + event.getPointerCount()
                    + ')';
        }
        if (inputEvent instanceof KeyEvent) {
            KeyEvent event = (KeyEvent) inputEvent;
            return "KeyEvent(action=" + event.getAction()
                    + ",keyCode=" + event.getKeyCode()
                    + ')';
        }
        return inputEvent.getClass().getName();
    }

    private static synchronized boolean shouldLogInjectionDiagnostics() {
        long now = System.currentTimeMillis();
        if (lastInjectionDiagnosticLogDate > now - DIAGNOSTIC_LOG_INTERVAL_MS) {
            return false;
        }
        lastInjectionDiagnosticLogDate = now;
        return true;
    }

    private String injectionDiagnostics(String reason, int requestedDisplayId) {
        if (!shouldLogInjectionDiagnostics()) {
            return "";
        }

        return " diagnostics(reason=" + reason
                + " package=" + FakeContext.PACKAGE_NAME
                + " pid=" + Process.myPid()
                + " uid=" + Process.myUid()
                + " binderCallingUid=" + Binder.getCallingUid()
                + " thread=" + Thread.currentThread().getName()
                + " managerClass=" + manager.getClass().getName()
                + " android=" + Build.VERSION.RELEASE + "/" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + '/' + Build.MODEL
                + " fingerprint=" + Build.FINGERPRINT
                + displaySnapshot(requestedDisplayId)
                + ')';
    }

    private static String displaySnapshot(int requestedDisplayId) {
        return displayIdSnapshot(requestedDisplayId) + targetDisplaySnapshot(requestedDisplayId);
    }

    private static String displayIdSnapshot(int requestedDisplayId) {
        try {
            int[] ids = ServiceManager.getDisplayManager().getDisplayIds();
            StringBuilder builder = new StringBuilder(" displayIds=[");
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(ids[i]);
                if (ids[i] == requestedDisplayId) {
                    builder.append('*');
                }
            }
            return builder.append(']').toString();
        } catch (Throwable t) {
            return " displayIds=unavailable:" + t.getClass().getName() + ':' + t.getMessage();
        }
    }

    private static String targetDisplaySnapshot(int requestedDisplayId) {
        if (requestedDisplayId < 0) {
            return " targetDisplay=unknown";
        }
        try {
            DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(requestedDisplayId);
            if (info == null) {
                return " targetDisplay=missing";
            }
            return " targetDisplay=present"
                    + " size=" + info.size().width() + 'x' + info.size().height()
                    + " rotation=" + info.rotation()
                    + " layerStack=" + info.layerStack()
                    + " flags=0x" + Integer.toHexString(info.flags())
                    + " dpi=" + info.dpi()
                    + " uniqueId=" + info.uniqueId();
        } catch (Throwable t) {
            return " targetDisplay=lookup_failed:" + t.getClass().getName() + ':' + t.getMessage();
        }
    }

    public static String getDisplayIdForLog(InputEvent inputEvent) {
        try {
            if (getDisplayIdMethod == null) {
                getDisplayIdMethod = InputEvent.class.getMethod("getDisplayId");
            }
            return String.valueOf(getDisplayIdMethod.invoke(inputEvent));
        } catch (ReflectiveOperationException e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static int getDisplayIdForDiagnostics(InputEvent inputEvent) {
        try {
            if (getDisplayIdMethod == null) {
                getDisplayIdMethod = InputEvent.class.getMethod("getDisplayId");
            }
            Object displayId = getDisplayIdMethod.invoke(inputEvent);
            return displayId instanceof Integer ? (Integer) displayId : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static Method getSetDisplayIdMethod() throws NoSuchMethodException {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        return setDisplayIdMethod;
    }

    public static boolean setDisplayId(InputEvent inputEvent, int displayId) {
        try {
            Method method = getSetDisplayIdMethod();
            method.invoke(inputEvent, displayId);
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot associate a display id to the input event"
                            + " requestedDisplayId=" + displayId
                            + " eventDisplayId=" + getDisplayIdForLog(inputEvent)
                            + " event=" + describeInputEvent(inputEvent),
                    e);
            return false;
        }
    }

    private static Method getSetActionButtonMethod() throws NoSuchMethodException {
        if (setActionButtonMethod == null) {
            setActionButtonMethod = MotionEvent.class.getMethod("setActionButton", int.class);
        }
        return setActionButtonMethod;
    }

    public static boolean setActionButton(MotionEvent motionEvent, int actionButton) {
        try {
            Method method = getSetActionButtonMethod();
            method.invoke(motionEvent, actionButton);
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot set action button on MotionEvent", e);
            return false;
        }
    }

    private static Method getAddUniqueIdAssociationByPortMethod() throws NoSuchMethodException {
        if (addUniqueIdAssociationByPortMethod == null) {
            addUniqueIdAssociationByPortMethod = android.hardware.input.InputManager.class.getMethod(
                    "addUniqueIdAssociationByPort", String.class, String.class);
        }
        return addUniqueIdAssociationByPortMethod;
    }

    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    public void addUniqueIdAssociationByPort(String inputPort, String uniqueId) {
        try {
            Method method = getAddUniqueIdAssociationByPortMethod();
            method.invoke(manager, inputPort, uniqueId);
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot add unique id association by port", e);
        }
    }

    private static Method getRemoveUniqueIdAssociationByPortMethod() throws NoSuchMethodException {
        if (removeUniqueIdAssociationByPortMethod == null) {
            removeUniqueIdAssociationByPortMethod = android.hardware.input.InputManager.class.getMethod(
                    "removeUniqueIdAssociationByPort", String.class);
        }
        return removeUniqueIdAssociationByPortMethod;
    }

    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    public void removeUniqueIdAssociationByPort(String inputPort) {
        try {
            Method method = getRemoveUniqueIdAssociationByPortMethod();
            method.invoke(manager, inputPort);
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot remove unique id association by port", e);
        }
    }
}
