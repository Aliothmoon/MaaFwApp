#ifndef BRIDGE_INPUT_H
#define BRIDGE_INPUT_H

#include "bridge_internal.h"

bool InitInputBridge(JavaVM *vm, JNIEnv *env, const char *driverClassName);
void ReleaseInputBridge(JNIEnv *env);
void SetInputContactSupport(bool supported);

#endif // BRIDGE_INPUT_H
