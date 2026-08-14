package com.aliothmoon.maafw;

/**
 * 特权进程回投执行事件；app 侧 MaaFrameworkRunnerPort 转成 RunnerEvent / RunnerState
 * 全部 oneway：native 回调线程不能被 app 侧阻塞
 */
oneway interface IMaaRunnerCallback {

    /** MaaFramework 的原始通知（MaaMsg.h 的 message + details_json） */
    void onEvent(String message, String detailsJson) = 1;

    void onTaskStarted(String taskName, int index, int total) = 2;

    void onTaskFinished(String taskName, boolean success, String message) = 3;

    /** outcome 取 RunOutcome 的取值；reason 仅在整轮失败时非空 */
    void onFinished(int outcome, String reason) = 4;

    void onAgentOutput(String line, boolean fromStderr) = 5;

    /** 一个 agent child 已 connect（含本轮复用已在线的）；[exec] 是 PI 的 child_exec */
    void onAgentConnected(int index, int total, String exec) = 6;
}
