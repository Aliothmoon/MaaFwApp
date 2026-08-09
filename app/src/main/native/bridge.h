#ifndef NATIVE_LIB_H
#define NATIVE_LIB_H

#include <jni.h>

#include <cstddef>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

#define BRIDGE_API __attribute__((visibility("default")))

struct FrameInfo {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t length;
    void *data;
    void *frame_ref;
};

enum MethodType {
    START_GAME = 1,
    STOP_GAME = 2,
    INPUT = 4,
    TOUCH_DOWN = 6,
    TOUCH_MOVE = 7,
    TOUCH_UP = 8,
    KEY_DOWN = 9,
    KEY_UP = 10
};

struct Position {
    int x;
    int y;
};

struct StartGameArgs {
    const char *package_name;
    int force_stop;
};

struct StopGameArgs {
    const char *client_type;
};

struct InputArgs {
    const char *text;
};

struct TouchArgs {
    Position p;
};

struct KeyArgs {
    int key_code;
};

union ArgUnion {
    StartGameArgs start_game;
    StopGameArgs stop_game;
    InputArgs input;
    TouchArgs touch;
    KeyArgs key;
};

struct MethodParam {
    int display_id;
    MethodType method;
    ArgUnion args;
};

BRIDGE_API FrameInfo GetLockedPixels(void);
BRIDGE_API int UnlockPixels(FrameInfo info);
BRIDGE_API int DispatchInputMessage(MethodParam param);

#ifdef __cplusplus
}

bool CheckJNIException(JNIEnv *env, const char *context);

#endif

#endif // NATIVE_LIB_H
