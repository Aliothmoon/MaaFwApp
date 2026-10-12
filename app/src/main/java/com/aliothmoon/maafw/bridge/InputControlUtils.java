package com.aliothmoon.maafw.bridge;

import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.aliothmoon.maafw.ITouchEventCallback;
import com.aliothmoon.maafw.third.DisplayInfo;
import com.aliothmoon.maafw.third.Ln;
import com.aliothmoon.maafw.third.wrappers.InputManager;
import com.aliothmoon.maafw.third.wrappers.ServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 多指触控注入：{@link TouchPointerSequence} 规划 MotionEvent，这里注入并维护槽位
 */
public final class InputControlUtils {

    private static final String TAG = "InputControlUtils";
    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
    private static final MotionEvent.PointerProperties[] POINTER_PROPERTIES =
            new MotionEvent.PointerProperties[TouchPointerSequence.MAX_CONTACTS];
    private static final MotionEvent.PointerCoords[] POINTER_COORDS =
            new MotionEvent.PointerCoords[TouchPointerSequence.MAX_CONTACTS];
    private static final String STAGE_SET_DISPLAY_ID = "SET_DISPLAY_ID";
    private static final String STAGE_INJECT = "INJECT_INPUT_EVENT";

    /**
     * DROPPED：目标 display 还在，系统却没收下事件，多是目标窗口这一刻不收触摸（加载、联网遮罩）；
     * FAILED：注入通路本身不可用（display 已回收、设不上 displayId、contact 越界）
     */
    public enum TouchResult { DELIVERED, DROPPED, FAILED }

    private static InputManager manager;
    private static volatile ITouchEventCallback touchCallback;
    /**
     * 在场手指；只整体换引用，注入失败时保持与系统侧一致
     */
    private static List<TouchPointerSequence.Pointer> slots = Collections.emptyList();
    private static long gestureDownTime = 0;
    /**
     * 解锁注入期间 >0：失败日志不带键码与坐标、不回调触控预览，PIN 与手势轨迹不能进可导出的日志；
     * 进程级而非线程级，任务线程此刻的失败日志也会打出 slots
     */
    private static final AtomicInteger SENSITIVE_DEPTH = new AtomicInteger();
    private static final String REDACTED = "<redacted>";

    static {
        for (int i = 0; i < TouchPointerSequence.MAX_CONTACTS; i++) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;
            POINTER_PROPERTIES[i] = props;
            POINTER_COORDS[i] = new MotionEvent.PointerCoords();
        }
    }
    private InputControlUtils() {
    }

    private static InputManager getManager() {
        if (manager == null) {
            manager = ServiceManager.getInputManager();
        }
        return manager;
    }

    /**
     * liftingIndex 为正在抬起的手指（压力置 0），无则传 -1；CANCEL 全部置 0
     */
    private static MotionEvent obtainEvent(List<TouchPointerSequence.Pointer> pointers, long eventTime,
                                           int action, int liftingIndex) {
        boolean cancel = (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_CANCEL;
        int n = pointers.size();
        for (int i = 0; i < n; i++) {
            TouchPointerSequence.Pointer p = pointers.get(i);
            // contact 隔离手动与自动操作；发给应用的 pointerId 独立分配并保持稳定。
            POINTER_PROPERTIES[i].id = p.getPointerId();
            MotionEvent.PointerCoords coord = POINTER_COORDS[i];
            coord.x = Math.max(0, p.getX());
            coord.y = Math.max(0, p.getY());
            coord.pressure = (cancel || i == liftingIndex) ? 0.0f : 1.0f;
            coord.size = 1.0f;
        }
        return MotionEvent.obtain(
                gestureDownTime, eventTime, action,
                n, POINTER_PROPERTIES, POINTER_COORDS,
                0, 0,
                1.0f, 1.0f,
                DEFAULT_DEVICE_ID, 0, DEFAULT_SOURCE, 0
        );
    }

    /**
     * reportIndex 为本次事件发生变化的手指，触控预览只上报这一根；pointers 是 event 的来源，只用于失败日志
     */
    private static TouchResult inject(MotionEvent event, List<TouchPointerSequence.Pointer> pointers,
                                      int displayId, int mode, int reportIndex) {
        try {
            if (!setDisplayId(event, displayId)) {
                logTouchFailure(event, pointers, reportIndex, displayId, mode, STAGE_SET_DISPLAY_ID, -1,
                        TouchResult.FAILED);
                return TouchResult.FAILED;
            }
            notifyTouchCallback(event, reportIndex);
            long start = SystemClock.elapsedRealtimeNanos();
            if (getManager().injectInputEvent(event, mode)) {
                return TouchResult.DELIVERED;
            }
            long injectNanos = SystemClock.elapsedRealtimeNanos() - start;
            TouchResult result = isDisplayPresent(displayId) ? TouchResult.DROPPED : TouchResult.FAILED;
            logTouchFailure(event, pointers, reportIndex, displayId, mode, STAGE_INJECT, injectNanos, result);
            return result;
        } finally {
            event.recycle();
        }
    }

    public static void setTouchCallback(ITouchEventCallback callback) {
        touchCallback = callback;
    }

    /** 必须与 {@link #endSensitiveInput()} 成对，放在 try/finally 里 */
    public static void beginSensitiveInput() {
        SENSITIVE_DEPTH.incrementAndGet();
    }

    public static void endSensitiveInput() {
        SENSITIVE_DEPTH.decrementAndGet();
    }

    private static boolean isSensitiveInput() {
        return SENSITIVE_DEPTH.get() > 0;
    }

    /** 还有手指按着：可能是任务正在点，槽位是进程共享的 */
    public static synchronized boolean hasActiveContacts() {
        return !slots.isEmpty();
    }

    private static void notifyTouchCallback(MotionEvent event, int index) {
        ITouchEventCallback callback = touchCallback;
        if (callback == null || isSensitiveInput()) {
            return;
        }
        try {
            callback.onCallback(Math.round(event.getX(index)), Math.round(event.getY(index)),
                    event.getActionMasked(), event.getPointerId(index));
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

    private static List<TouchPointerSequence.Pointer> without(List<TouchPointerSequence.Pointer> pointers,
                                                              int index) {
        List<TouchPointerSequence.Pointer> next = new ArrayList<>(pointers);
        next.remove(index);
        return next;
    }

    private static void cancelGesture(int displayId) {
        if (!slots.isEmpty()) {
            MotionEvent cancel = obtainEvent(slots, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, -1);
            inject(cancel, slots, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, 0);
        }
        slots = Collections.emptyList();
    }

    private static TouchResult injectStep(TouchPointerSequence.Step step, int displayId) {
        if (step.getCancelFirst()) {
            cancelGesture(displayId);
        }
        long now = SystemClock.uptimeMillis();
        if (slots.isEmpty()) {
            gestureDownTime = now;
        }
        int masked = step.getActionMasked();
        int index = step.getChangingIndex();
        List<TouchPointerSequence.Pointer> pointers = step.getPointers();
        boolean isDown = masked == TouchPointerSequence.ACTION_DOWN
                || masked == TouchPointerSequence.ACTION_POINTER_DOWN;
        boolean isUp = masked == TouchPointerSequence.ACTION_UP
                || masked == TouchPointerSequence.ACTION_POINTER_UP;
        List<TouchPointerSequence.Pointer> next = masked == TouchPointerSequence.ACTION_UP
                ? Collections.<TouchPointerSequence.Pointer>emptyList()
                : masked == TouchPointerSequence.ACTION_POINTER_UP ? without(pointers, index) : pointers;

        // DOWN 必须 WAIT_FOR_FINISH，确保起始状态被系统接收
        TouchResult result = inject(
                obtainEvent(pointers, now, encodeAction(masked, index), isUp ? index : -1),
                pointers,
                displayId,
                isDown ? InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                        : InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,
                index);
        // 未送达则槽位不动；之后同 contact 再按下会先整体 CANCEL 自愈
        if (result == TouchResult.DELIVERED) {
            slots = next;
        }
        return result;
    }

    private static synchronized TouchResult apply(TouchPointerSequence.Kind kind, int x, int y, int contact,
                                                  int displayId) {
        TouchPointerSequence.Step step = TouchPointerSequence.INSTANCE.plan(kind, slots, contact, x, y);
        if (!step.getOk()) {
            logPlanFailure(step, kind, x, y, contact, displayId);
            return TouchResult.FAILED;
        }
        if (step.getNoop()) {
            return TouchResult.DELIVERED;
        }
        return injectStep(step, displayId);
    }

    public static TouchResult down(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Down, x, y, contact, displayId);
    }

    public static boolean move(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Move, x, y, contact, displayId) == TouchResult.DELIVERED;
    }

    public static boolean up(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Up, x, y, contact, displayId) == TouchResult.DELIVERED;
    }

    private static KeyEvent obtainKeyEvent(long downTime, long eventTime, int action, int keyCode) {
        return new KeyEvent(
                downTime,
                eventTime,
                action,
                keyCode,
                0,
                0,
                DEFAULT_DEVICE_ID,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
        );
    }

    private static boolean injectKey(KeyEvent keyEvent, int displayId, int injectMode) {
        if (!setDisplayId(keyEvent, displayId)) {
            logKeyFailure(keyEvent, displayId, injectMode, STAGE_SET_DISPLAY_ID, -1);
            return false;
        }

        long start = SystemClock.elapsedRealtimeNanos();
        boolean injected = getManager().injectInputEvent(keyEvent, injectMode);
        if (!injected) {
            logKeyFailure(keyEvent, displayId, injectMode, STAGE_INJECT,
                    SystemClock.elapsedRealtimeNanos() - start);
        }
        return injected;
    }

    public static boolean keyDown(int keyCode, int displayId) {
        long downTime = SystemClock.uptimeMillis();
        return injectKey(
                obtainKeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode),
                displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    public static boolean keyUp(int keyCode, int displayId) {
        long downTime = SystemClock.uptimeMillis();
        return injectKey(
                obtainKeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode),
                displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /**
     * 成对注入一次按键；UP 使用与 DOWN 相同的 downTime，确保系统按同一次按压处理
     */
    public static boolean pressKey(int keyCode, int displayId) {
        long downTime = SystemClock.uptimeMillis();
        boolean down = injectKey(
                obtainKeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode),
                displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        boolean up = injectKey(
                obtainKeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode),
                displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        return down && up;
    }

    /** 最后一个事件等目标窗口处理完才返回，紧接着的点击不能抢在文字送达之前；失败日志不带键码，能拼回原文 */
    public static boolean injectTextKeys(KeyEvent[] events, int displayId) {
        for (int i = 0; i < events.length; i++) {
            KeyEvent event = events[i];
            if (!setDisplayId(event, displayId)) {
                Ln.w(TAG + ": text key inject failed stage=" + STAGE_SET_DISPLAY_ID
                        + " index=" + i + "/" + events.length + " displayId=" + displayId);
                return false;
            }
            int mode = i == events.length - 1
                    ? InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                    : InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;
            if (!getManager().injectInputEvent(event, mode)) {
                Ln.w(TAG + ": text key inject failed stage=" + STAGE_INJECT
                        + " index=" + i + "/" + events.length + " displayId=" + displayId
                        + " mode=" + injectModeName(mode) + displaySnapshot(displayId));
                return false;
            }
        }
        return true;
    }

    private static void logPlanFailure(TouchPointerSequence.Step step, TouchPointerSequence.Kind kind,
                                       int x, int y, int contact, int displayId) {
        Ln.w(TAG + ": touch plan failed"
                + " reason=" + step.getFailureReason()
                + " kind=" + kind
                + " contact=" + contact
                + " x=" + (isSensitiveInput() ? REDACTED : String.valueOf(x))
                + " y=" + (isSensitiveInput() ? REDACTED : String.valueOf(y))
                + " displayId=" + displayId
                + " slots=" + formatPointers(slots));
    }

    /**
     * injectNanos 为 injectInputEvent 本身的耗时，没走到注入传 -1
     */
    private static void logTouchFailure(MotionEvent event, List<TouchPointerSequence.Pointer> pointers,
                                        int reportIndex, int displayId, int mode, String stage,
                                        long injectNanos, TouchResult result) {
        Ln.w(TAG + ": touch inject failed"
                + " result=" + result
                + " stage=" + stage
                + " action=" + actionName(event.getActionMasked())
                + " changingIndex=" + reportIndex
                + " displayId=" + displayId
                + " eventDisplayId=" + InputManager.getDisplayIdForLog(event)
                + " mode=" + injectModeName(mode)
                + elapsedSuffix(injectNanos)
                + " pointers=" + formatPointers(pointers)
                + " slots=" + formatPointers(slots)
                + displaySnapshot(displayId));
    }

    private static void logKeyFailure(KeyEvent event, int displayId, int mode, String stage, long injectNanos) {
        Ln.w(TAG + ": key inject failed"
                + " stage=" + stage
                + " action=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + " keyCode=" + (isSensitiveInput() ? REDACTED : String.valueOf(event.getKeyCode()))
                + " displayId=" + displayId
                + " eventDisplayId=" + InputManager.getDisplayIdForLog(event)
                + " mode=" + injectModeName(mode)
                + elapsedSuffix(injectNanos)
                + displaySnapshot(displayId));
    }

    /**
     * contact 是调用方的手指编号，pointerId 是发给系统的编号，两者已解耦，都要打出来
     */
    private static String formatPointers(List<TouchPointerSequence.Pointer> pointers) {
        boolean redact = isSensitiveInput();
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < pointers.size(); i++) {
            TouchPointerSequence.Pointer pointer = pointers.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("contact=").append(pointer.getContact())
                    .append("/pointerId=").append(pointer.getPointerId());
            if (redact) {
                builder.append('@').append(REDACTED);
            } else {
                builder.append('@').append(pointer.getX())
                        .append(',').append(pointer.getY());
            }
        }
        return builder.append(']').toString();
    }

    /** 查不到 display 列表时按不在算，宁可报失败也不把通路故障当成点击被吞 */
    private static boolean isDisplayPresent(int displayId) {
        if (displayId == 0) {
            return true;
        }
        try {
            for (int id : ServiceManager.getDisplayManager().getDisplayIds()) {
                if (id == displayId) {
                    return true;
                }
            }
        } catch (RuntimeException | AssertionError e) {
            Ln.w(TAG + ": getDisplayIds failed: " + e.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * 虚拟屏被系统回收后注入必然失败，失败时记下此刻还有哪些 display；目标不在列表里就不再查详情，
     * 免得 getDisplayInfo 拿到 null 后退到 dumpsys
     */
    private static String displaySnapshot(int displayId) {
        int[] ids;
        try {
            ids = ServiceManager.getDisplayManager().getDisplayIds();
        } catch (RuntimeException | AssertionError e) {
            return " displays=unavailable:" + e.getClass().getSimpleName();
        }
        StringBuilder builder = new StringBuilder(" displays=[");
        boolean present = false;
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(ids[i]);
            if (ids[i] == displayId) {
                builder.append('*');
                present = true;
            }
        }
        builder.append(']');
        if (!present) {
            return builder.append(" target=missing").toString();
        }
        DisplayInfo info;
        try {
            info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        } catch (RuntimeException | AssertionError e) {
            return builder.append(" target=unavailable:").append(e.getClass().getSimpleName()).toString();
        }
        if (info == null) {
            return builder.append(" target=missing").toString();
        }
        return builder.append(" target=").append(info.size().width()).append('x').append(info.size().height())
                .append(" rotation=").append(info.rotation())
                .append(" layerStack=").append(info.layerStack())
                .append(" flags=0x").append(Integer.toHexString(info.flags()))
                .append(" dpi=").append(info.dpi())
                .append(" uniqueId=").append(info.uniqueId())
                .toString();
    }

    private static String actionName(int maskedAction) {
        switch (maskedAction) {
            case MotionEvent.ACTION_DOWN: return "DOWN";
            case MotionEvent.ACTION_UP: return "UP";
            case MotionEvent.ACTION_MOVE: return "MOVE";
            case MotionEvent.ACTION_CANCEL: return "CANCEL";
            case MotionEvent.ACTION_POINTER_DOWN: return "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP: return "POINTER_UP";
            default: return "UNKNOWN_" + maskedAction;
        }
    }

    private static String injectModeName(int mode) {
        switch (mode) {
            case InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: return "ASYNC";
            case InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT: return "WAIT_FOR_RESULT";
            case InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH: return "WAIT_FOR_FINISH";
            default: return "UNKNOWN_" + mode;
        }
    }

    private static String elapsedSuffix(long nanos) {
        return nanos < 0 ? "" : String.format(Locale.US, " elapsedMs=%.1f", nanos / 1_000_000.0);
    }
}
