#ifndef BRIDGE_CAPTURE_H
#define BRIDGE_CAPTURE_H

#include "bridge_internal.h"

#include <string>

jobject SetupNativeCapturer(JNIEnv *env, int width, int height);
void ReleaseNativeCapturer();

// 采集收尾停在哪一步，没在收尾返回空串；卡死诊断用，不加锁
std::string DescribeReleaseStage();

#endif // BRIDGE_CAPTURE_H
