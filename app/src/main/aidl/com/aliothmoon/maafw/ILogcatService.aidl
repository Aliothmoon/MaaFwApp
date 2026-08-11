package com.aliothmoon.maafw;

/**
 * 独立特权进程里的 logcat 抓取服务（docs/privileged-runtime.md）。
 * 跟主 RemoteService 一样走 Shizuku bindUserService 或 launcher 起的 root 进程，
 * 只是把 --class 换成 LogcatCaptureServiceImpl；transaction id 约定同 RemoteService
 */
interface ILogcatService {
    oneway void destroy() = 16777114;

    /** 抓 appPid 与 servicePid 的 logcat 到 userDir/debug/logcat/{core,app} */
    void startCapture(int appPid, int servicePid, String userDir) = 1;
}
