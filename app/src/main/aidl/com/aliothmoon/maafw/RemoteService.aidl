package com.aliothmoon.maafw;

import android.view.Surface;
import com.aliothmoon.maafw.ITouchEventCallback;
import com.aliothmoon.maafw.IMaaRunnerCallback;

/**
 * 特权进程的服务面（docs/privileged-runtime.md §6）
 *
 * transaction id 必须显式写死且只增不改：app 升级后旧特权进程可能仍存活，
 * 双方按 transaction code 通信，改动方法顺序会错位
 * destroy() 的 16777114 是 Shizuku user service 的保留 id
 */
interface RemoteService {

    oneway void destroy() = 16777114;

    // ── 生命周期 ──
    void exit() = 1;

    String version() = 2;

    int pid() = 3;

    /** app 侧上报自己的 pid，特权进程据此做 /proc 看门狗，app 消失即自杀 */
    oneway void heartbeat(int appPid) = 4;

    /**
     * piRoot 是解包后的 PI 根目录绝对路径
     * logDir 交给 MaaFramework 落 maa.log 与 Screencap 动作的产物；不设时它按进程 CWD 算，特权进程的 CWD 不可写
     */
    boolean setup(String piRoot, String logDir, boolean isDebug) = 5;

    // ── 显示 ──
    boolean setVirtualDisplayMode(int mode) = 10;

    void setVirtualDisplayResolution(int width, int height, int dpi) = 11;

    /** 返回 display id，失败返回 -1 */
    int startVirtualDisplay() = 12;

    void stopVirtualDisplay() = 13;

    boolean isAppOnVirtualDisplay(String packageName) = 14;

    boolean moveAppToVirtualDisplay(String packageName) = 15;

    oneway void setForceFullscreenOnVirtualDisplay(boolean enabled) = 16;

    oneway void setDisplayPower(boolean on) = 17;

    /**
     * 强改**主屏**分辨率；前台模式要求主屏是 16:9，这是唯一的改法
     * 与 setVirtualDisplayResolution 不是一回事：那个改的是后台虚拟屏，改完只影响虚拟屏上的应用
     */
    boolean setForcedDisplaySize(int width, int height) = 18;

    /** 撤掉 setForcedDisplaySize，主屏回到出厂分辨率 */
    boolean clearForcedDisplaySize() = 19;

    // ── 预览 ──
    void setMonitorSurface(in Surface surface) = 20;

    oneway void setTouchCallback(ITouchEventCallback callback) = 21;

    // ── 预览上的手动操作 ──
    oneway void touchDown(int x, int y) = 30;

    oneway void touchMove(int x, int y) = 31;

    oneway void touchUp(int x, int y) = 32;

    // ── 代授权限 ──

    /**
     * 用特权身份给 app 自己授权，省掉用户逐个点系统页
     *
     * permissions 与返回值都是 PrivilegedGrant 的位掩码，返回实际授到的那些
     * 用位掩码而不是 Parcelable：Parcelable 的线格式跟着 Kotlin 类布局走，
     * app 升级但旧特权进程仍存活时会解不出来（同本文件开头的 transaction id 约定）
     */
    int grantPermissions(String packageName, int uid, int permissions) = 41;

    // ── 目标应用 ──
    boolean isPackageInstalled(String packageName) = 40;

    // ── 执行 ──
    oneway void setRunnerCallback(IMaaRunnerCallback callback) = 50;

    /** payload 是 RunPlanPayload 的 JSON；立即返回是否受理，进度与结果走回调 */
    boolean startRun(String runPlanJson) = 51;

    /** 幂等；未在跑时也返回 true */
    boolean stopRun() = 52;

    boolean isRunning() = 53;

    /** MaaFramework 版本；未加载返回 null */
    String maaVersion() = 54;

    /** 看门狗状态：0=IDLE / 1=WATCHING / 2=APP_DIED（目标 app 是否仍在虚拟屏上） */
    int watchdogState() = 60;

    /** 看门狗当下盯着的包名；没有目标时为空串。运行日志要把它写进那句提示里 */
    String watchdogTargetPackage() = 61;

    // ── 亮屏与解锁 ──

    /**
     * 亮屏并解除锁屏；credential 是纯数字 PIN，无凭证锁屏传空串
     * 返回值见 WakeUnlockResult。整段在特权进程内同步完成——息屏后 app 侧协程会被挂起
     */
    int unlock(String credential) = 70;

    /** 设置页自测：先上锁息屏再解一次 */
    int testUnlock(String credential) = 71;

    /** 上锁并息屏；跑完自动熄屏用它 */
    int lockAndSleep() = 72;

    /**
     * 强停虚拟屏上的目标应用；包名取自看门狗运行期反推的那个
     * 放在特权侧而不是让 app 传包名：外壳不维护包名表，运行期只有这边知道
     */
    boolean stopTargetApp() = 73;

    /** 屏幕当前是否亮着；采「本轮开始时手机是不是醒着」用它 */
    boolean isScreenOn() = 74;

    /**
     * 把 controller 的缓存截图落到 [path]，供 focus 模板的 {image} 用
     *
     * 走文件不回传字节：一张 720p PNG 几百 KB，binder 事务缓冲总共才 1MB
     */
    boolean saveCachedImage(String path) = 75;
}
