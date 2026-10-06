package com.aliothmoon.maafw;

/** 同步调用：DispatchInputMessage 要拿到结果回给 MaaFramework；transaction id 规则同 RemoteService */
interface ITextInputSink {
    /** targetPackage 为空串表示本轮没有 StartApp 过，不做包名校验 */
    int setText(int displayId, String targetPackage, String text) = 1;
}
