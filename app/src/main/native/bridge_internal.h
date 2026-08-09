#ifndef BRIDGE_INTERNAL_H
#define BRIDGE_INTERNAL_H

#include "bridge.h"

#include <android/log.h>

#define LOG_TAG "LibBridge"

#ifdef NDEBUG
#define LOGD(...) ((void) 0)
#else
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#endif // BRIDGE_INTERNAL_H
