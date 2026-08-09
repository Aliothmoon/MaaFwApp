#include <unistd.h>
#include <cstring>
#include "bridge_input.h"

static JavaVM *g_jvm = nullptr;
static jclass g_driver_clz = nullptr;
static jmethodID g_touch_down_method = nullptr;
static jmethodID g_touch_move_method = nullptr;
static jmethodID g_touch_up_method = nullptr;
static jmethodID g_key_down_method = nullptr;
static jmethodID g_key_up_method = nullptr;
static jmethodID g_start_app_method = nullptr;

/* upcall 落到 DriverClass -> InputControlUtils/ActivityUtils，那边全是对隐藏 API 的反射，
 * 各家 ROM 上抛异常是常态。异常挂在 JNIEnv 上不清掉，下一次 JNI 调用就是未定义行为——
 * 实测表现为下一轮 upcall 开头的 NewStringUTF 里 SIGSEGV。每次 upcall 后必须清。 */
static int FinishUpcall(JNIEnv *env, jboolean result, const char *context) {
    if (CheckJNIException(env, context)) {
        return -1;
    }
    return result ? 0 : -1;
}

static int
UpcallInputControl(JNIEnv *env, MethodType method, int x, int y, int keyCode, int displayId) {
    if (!env || !g_driver_clz) {
        return -1;
    }

    switch (method) {
        case TOUCH_DOWN:
            return FinishUpcall(env,
                                env->CallStaticBooleanMethod(g_driver_clz, g_touch_down_method, x,
                                                             y, displayId),
                                "DriverClass.touchDown");
        case TOUCH_MOVE:
            return FinishUpcall(env,
                                env->CallStaticBooleanMethod(g_driver_clz, g_touch_move_method, x,
                                                             y, displayId),
                                "DriverClass.touchMove");
        case TOUCH_UP:
            return FinishUpcall(env,
                                env->CallStaticBooleanMethod(g_driver_clz, g_touch_up_method, x, y,
                                                             displayId),
                                "DriverClass.touchUp");
        case KEY_DOWN:
            return FinishUpcall(env,
                                env->CallStaticBooleanMethod(g_driver_clz, g_key_down_method,
                                                             keyCode, displayId),
                                "DriverClass.keyDown");
        case KEY_UP:
            return FinishUpcall(env,
                                env->CallStaticBooleanMethod(g_driver_clz, g_key_up_method, keyCode,
                                                             displayId),
                                "DriverClass.keyUp");
        default:
            return -1;
    }
}

static int UpcallStartApp(JNIEnv *env, const char *packageName, int displayId, bool forceStop) {
    if (!env || !packageName || !g_driver_clz || !g_start_app_method) {
        LOGE("UpcallStartApp: not ready env=%p pkg=%p clz=%p mid=%p",
             (void *) env, (void *) packageName, (void *) g_driver_clz, (void *) g_start_app_method);
        return -1;
    }

    /* 上游传进来的是 std::string::c_str()，理论上带 NUL；出过越界读就把长度打出来定位 */
    LOGI("UpcallStartApp: env=%p len=%zu display=%d forceStop=%d",
         (void *) env, strnlen(packageName, 4096), displayId, (int) forceStop);

    jstring jPackageName = env->NewStringUTF(packageName);
    if (!jPackageName || CheckJNIException(env, "NewStringUTF(packageName)")) {
        return -1;
    }
    jboolean result = env->CallStaticBooleanMethod(g_driver_clz, g_start_app_method, jPackageName,
                                                   displayId, static_cast<jboolean>(forceStop));
    int ret = FinishUpcall(env, result, "DriverClass.startApp");
    env->DeleteLocalRef(jPackageName);
    return ret;
}

bool InitInputBridge(JavaVM *vm, JNIEnv *env, const char *driverClassName) {
    g_jvm = vm;
    LOGI("InitInputBridge: vm=%p env=%p class=%s", (void *) vm, (void *) env, driverClassName);
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
// JNA callback 发力了不得不这样做了
struct JniThreadDetacher {
    bool armed = false;

    ~JniThreadDetacher() {
        if (armed && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
    }
};


static JNIEnv *GetJNIEnv() {
    if (!g_jvm) {
        return nullptr;
    }
    JNIEnv *env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
        return env;
    }
    if (g_jvm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK || !env) {
        LOGE("GetJNIEnv: attach failed for thread %d", gettid());
        return nullptr;
    }
    thread_local JniThreadDetacher detacher;
    detacher.armed = true;
    return env;
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
