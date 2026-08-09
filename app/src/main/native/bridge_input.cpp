#include <unistd.h>
#include "bridge_input.h"

static JavaVM *g_jvm = nullptr;
static jclass g_driver_clz = nullptr;
static jmethodID g_touch_down_method = nullptr;
static jmethodID g_touch_move_method = nullptr;
static jmethodID g_touch_up_method = nullptr;
static jmethodID g_key_down_method = nullptr;
static jmethodID g_key_up_method = nullptr;
static jmethodID g_start_app_method = nullptr;

static int
UpcallInputControl(JNIEnv *env, MethodType method, int x, int y, int keyCode, int displayId) {
    if (!env || !g_driver_clz) {
        return -1;
    }

    switch (method) {
        case TOUCH_DOWN:
            return env->CallStaticBooleanMethod(g_driver_clz, g_touch_down_method, x, y, displayId)
                   ? 0 : -1;
        case TOUCH_MOVE:
            return env->CallStaticBooleanMethod(g_driver_clz, g_touch_move_method, x, y, displayId)
                   ? 0 : -1;
        case TOUCH_UP:
            return env->CallStaticBooleanMethod(g_driver_clz, g_touch_up_method, x, y, displayId)
                   ? 0 : -1;
        case KEY_DOWN:
            return env->CallStaticBooleanMethod(g_driver_clz, g_key_down_method, keyCode, displayId)
                   ? 0 : -1;
        case KEY_UP:
            return env->CallStaticBooleanMethod(g_driver_clz, g_key_up_method, keyCode, displayId)
                   ? 0 : -1;
        default:
            return -1;
    }
}

static int UpcallStartApp(JNIEnv *env, const char *packageName, int displayId, bool forceStop) {
    if (!env || !packageName || !g_driver_clz || !g_start_app_method) {
        return -1;
    }

    jstring jPackageName = env->NewStringUTF(packageName);
    jboolean result = env->CallStaticBooleanMethod(g_driver_clz, g_start_app_method, jPackageName,
                                                   displayId, static_cast<jboolean>(forceStop));
    env->DeleteLocalRef(jPackageName);
    return result ? 0 : -1;
}

bool InitInputBridge(JavaVM *vm, JNIEnv *env, const char *driverClassName) {
    g_jvm = vm;
    if (!env || !driverClassName) {
        return false;
    }

    jclass driverClass = env->FindClass(driverClassName);
    if (!driverClass || CheckJNIException(env, "FindClass(driverClassName)")) {
        return false;
    }

    g_driver_clz = static_cast<jclass>(env->NewGlobalRef(driverClass));
    env->DeleteLocalRef(driverClass);
    if (!g_driver_clz) {
        return false;
    }

    g_touch_down_method = env->GetStaticMethodID(g_driver_clz, "touchDown", "(III)Z");
    g_touch_move_method = env->GetStaticMethodID(g_driver_clz, "touchMove", "(III)Z");
    g_touch_up_method = env->GetStaticMethodID(g_driver_clz, "touchUp", "(III)Z");
    g_key_down_method = env->GetStaticMethodID(g_driver_clz, "keyDown", "(II)Z");
    g_key_up_method = env->GetStaticMethodID(g_driver_clz, "keyUp", "(II)Z");
    g_start_app_method = env->GetStaticMethodID(g_driver_clz, "startApp", "(Ljava/lang/String;IZ)Z");

    if (CheckJNIException(env, "GetStaticMethodID(DriverClass)") ||
        !g_touch_down_method || !g_touch_move_method || !g_touch_up_method ||
        !g_key_down_method || !g_key_up_method || !g_start_app_method) {
        ReleaseInputBridge(env);
        return false;
    }

    return true;
}

void ReleaseInputBridge(JNIEnv *env) {
    g_touch_down_method = nullptr;
    g_touch_move_method = nullptr;
    g_touch_up_method = nullptr;
    g_key_down_method = nullptr;
    g_key_up_method = nullptr;
    g_start_app_method = nullptr;

    if (g_driver_clz && env) {
        env->DeleteGlobalRef(g_driver_clz);
    }
    g_driver_clz = nullptr;
    g_jvm = nullptr;
}

struct JniThreadAttacher {
    JNIEnv *env = nullptr;
    bool needs_detach = false;

    JniThreadAttacher() {
        if (!g_jvm) return;
        if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK) {
                needs_detach = true;
                LOGI("JniThreadAttacher: attached thread %d", gettid());
            } else {
                LOGE("JniThreadAttacher: attach failed for thread %d", gettid());
            }
        }
    }

    ~JniThreadAttacher() {
        if (needs_detach && g_jvm) {
            LOGI("JniThreadAttacher: detaching thread %d", gettid());
            g_jvm->DetachCurrentThread();
        }
    }
};

static JNIEnv *GetJNIEnv() {
    thread_local JniThreadAttacher attacher;
    return attacher.env;
}

BRIDGE_API int DispatchInputMessage(MethodParam param) {
    LOGD("DispatchInputMessage: method=%d display_id=%d", param.method, param.display_id);

    auto *env = GetJNIEnv();
    if (!env) {
        return -1;
    }

    switch (param.method) {
        case TOUCH_DOWN:
            return UpcallInputControl(env, TOUCH_DOWN, param.args.touch.p.x, param.args.touch.p.y,
                                      0, param.display_id);
        case TOUCH_MOVE:
            return UpcallInputControl(env, TOUCH_MOVE, param.args.touch.p.x, param.args.touch.p.y,
                                      0, param.display_id);
        case TOUCH_UP:
            return UpcallInputControl(env, TOUCH_UP, param.args.touch.p.x, param.args.touch.p.y, 0,
                                      param.display_id);
        case KEY_DOWN:
            return UpcallInputControl(env, KEY_DOWN, 0, 0, param.args.key.key_code,
                                      param.display_id);
        case KEY_UP:
            return UpcallInputControl(env, KEY_UP, 0, 0, param.args.key.key_code, param.display_id);
        case START_GAME:
            return UpcallStartApp(env, param.args.start_game.package_name, param.display_id,
                                  param.args.start_game.force_stop != 0);
        default:
            return 0;
    }
}
