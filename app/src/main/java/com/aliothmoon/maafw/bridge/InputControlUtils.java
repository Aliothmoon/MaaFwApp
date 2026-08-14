package com.aliothmoon.maafw.bridge;

import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.aliothmoon.maafw.ITouchEventCallback;
import com.aliothmoon.maafw.third.Ln;
import com.aliothmoon.maafw.third.wrappers.InputManager;
import com.aliothmoon.maafw.third.wrappers.ServiceManager;

import java.util.ArrayList;
import java.util.List;


public final class InputControlUtils {

    private static final String TAG = "InputControlUtils";

    private static InputManager manager;
    private static volatile ITouchEventCallback touchCallback;

    private static InputManager getManager() {
        if (manager == null) {
            manager = ServiceManager.getInputManager();
        }
        return manager;
    }

    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;

    private static final List<TouchPointerSequence.Pointer> slots = new ArrayList<>();
    private static long gestureDownTime = 0;

    private InputControlUtils() {
    }

    private static MotionEvent obtainFromSlots(long downTime, long eventTime, int action) {
        int n = slots.size();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            TouchPointerSequence.Pointer p = slots.get(i);
            MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
            prop.id = p.getContact();
            prop.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = prop;
            MotionEvent.PointerCoords coord = new MotionEvent.PointerCoords();
            coord.x = Math.max(0, p.getX());
            coord.y = Math.max(0, p.getY());
            coord.pressure = 1.0f;
            coord.size = 1.0f;
            coords[i] = coord;
        }
        return MotionEvent.obtain(
                downTime, eventTime, action,
                n, props, coords,
                0, 0,
                1.0f, 1.0f,
                DEFAULT_DEVICE_ID, 0, DEFAULT_SOURCE, 0
        );
    }

    private static boolean injectInputEvent(MotionEvent event, int displayId, int mode) {
        try {
            if (!setDisplayId(event, displayId)) {
                return false;
            }
            notifyTouchCallback(event);
            return getManager().injectInputEvent(event, mode);
        } finally {
            event.recycle();
        }
    }

    public static void setTouchCallback(ITouchEventCallback callback) {
        touchCallback = callback;
    }

    private static void notifyTouchCallback(MotionEvent event) {
        ITouchEventCallback callback = touchCallback;
        if (callback == null) {
            return;
        }
        try {
            callback.onCallback(Math.round(event.getX()), Math.round(event.getY()), event.getActionMasked());
        } catch (RemoteException | RuntimeException e) {
            touchCallback = null;
            Ln.w(TAG + ": touch callback failed, clearing registration", e);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean setDisplayId(InputEvent event, int displayId) {
        return displayId == 0 || InputManager.setDisplayId(event, displayId);
    }

    private static int encodeAction(int masked, int index) {
        if (masked == TouchPointerSequence.ACTION_POINTER_DOWN
                || masked == TouchPointerSequence.ACTION_POINTER_UP) {
            return masked | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        return masked;
    }

    private static void replaceSlots(List<TouchPointerSequence.Pointer> next) {
        slots.clear();
        slots.addAll(next);
    }

    private static void injectCancel(int displayId) {
        if (slots.isEmpty() || gestureDownTime == 0) {
            slots.clear();
            gestureDownTime = 0;
            return;
        }
        MotionEvent cancel = obtainFromSlots(
                gestureDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL);
        boolean result = injectInputEvent(cancel, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        slots.clear();
        gestureDownTime = 0;
    }

    private static boolean injectStep(TouchPointerSequence.Step step, int displayId) {
        if (!step.getOk()) {
            return false;
        }
        if (step.getCancelFirst()) {
            injectCancel(displayId);
        }
        replaceSlots(step.getPointers());
        if (slots.isEmpty()) {
            return false;
        }
        if (gestureDownTime == 0) {
            gestureDownTime = SystemClock.uptimeMillis();
        }
        int action = encodeAction(step.getActionMasked(), step.getChangingIndex());
        boolean wait = step.getActionMasked() == TouchPointerSequence.ACTION_DOWN
                || step.getActionMasked() == TouchPointerSequence.ACTION_POINTER_DOWN;
        int mode = wait
                ? InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                : InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;
        boolean result = injectInputEvent(
                obtainFromSlots(gestureDownTime, SystemClock.uptimeMillis(), action),
                displayId,
                mode);
        if (step.getActionMasked() == TouchPointerSequence.ACTION_UP) {
            slots.clear();
            gestureDownTime = 0;
        } else if (step.getActionMasked() == TouchPointerSequence.ACTION_POINTER_UP) {
            int idx = step.getChangingIndex();
            if (idx >= 0 && idx < slots.size()) {
                slots.remove(idx);
            }
        }
        return result;
    }

    public static synchronized boolean down(int x, int y, int contact, int displayId) {
        return injectStep(
                TouchPointerSequence.INSTANCE.plan(
                        TouchPointerSequence.Kind.Down,
                        new ArrayList<>(slots),
                        contact,
                        x,
                        y),
                displayId);
    }

    public static synchronized boolean move(int x, int y, int contact, int displayId) {
        return injectStep(
                TouchPointerSequence.INSTANCE.plan(
                        TouchPointerSequence.Kind.Move,
                        new ArrayList<>(slots),
                        contact,
                        x,
                        y),
                displayId);
    }

    public static synchronized boolean up(int x, int y, int contact, int displayId) {
        return injectStep(
                TouchPointerSequence.INSTANCE.plan(
                        TouchPointerSequence.Kind.Up,
                        new ArrayList<>(slots),
                        contact,
                        x,
                        y),
                displayId);
    }

    public static boolean keyDown(int keyCode, int displayId) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0);

        if (!setDisplayId(keyEvent, displayId)) {
            return false;
        }
        return getManager().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    public static boolean keyUp(int keyCode, int displayId) {
        long upTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(upTime, upTime, KeyEvent.ACTION_UP, keyCode, 0);

        if (!setDisplayId(keyEvent, displayId)) {
            return false;
        }

        return getManager().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
