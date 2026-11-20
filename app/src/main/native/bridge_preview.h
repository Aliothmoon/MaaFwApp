#ifndef BRIDGE_PREVIEW_H
#define BRIDGE_PREVIEW_H

#include "bridge_internal.h"

#include <media/NdkImage.h>

void SetPreviewSurface(JNIEnv *env, jobject jSurface);
void ShutdownPreview(JNIEnv *env);
bool IsPreviewEnabled();
bool DispatchPreview(AImage *image);
// 清掉排队的图并等渲染线程放下正在画的那张；超时返回 false，这时 reader 不能删
bool DrainPreviewQueue(int timeoutMs);

// 预览直接画 AImage，不经过帧缓冲；帧缓冲换成黑帧时要另外把它画黑，否则还停在残影上
// expectedFrameCount 之后来过新帧就不画
void BlankPreview(int64_t expectedFrameCount);

#endif // BRIDGE_PREVIEW_H
