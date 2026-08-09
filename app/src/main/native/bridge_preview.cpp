#include "bridge_preview.h"

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkImageReader.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <thread>

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;

struct EGLState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    GLuint program = 0;
    GLuint textureId = 0;
    bool initialized = false;
};

static const char *VERTEX_SHADER = R"(
attribute vec4 vPosition;
attribute vec2 vTexCoord;
varying vec2 fTexCoord;
void main() {
    gl_Position = vPosition;
    fTexCoord = vTexCoord;
}
)";

static const char *FRAGMENT_SHADER = R"(
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sTexture;
varying vec2 fTexCoord;
void main() {
    gl_FragColor = texture2D(sTexture, fTexCoord);
}
)";

static jobject g_previewSurfaceObj = nullptr;
static std::mutex g_previewMutex;
static std::atomic<bool> g_hasPreview{false};

static EGLState g_eglState;
static std::thread g_renderThread;
static std::queue<AImage *> g_renderQueue;
static std::mutex g_renderMutex;
static std::condition_variable g_renderCv;
static std::atomic<bool> g_renderThreadRunning{false};
static ANativeWindow *g_pendingWindow = nullptr;

static GLuint LoadShader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    return shader;
}

static void DrainPreviewQueueLocked() {
    while (!g_renderQueue.empty()) {
        AImage_delete(g_renderQueue.front());
        g_renderQueue.pop();
    }
}

static void DeinitEGL() {
    if (!g_eglState.initialized) {
        return;
    }

    if (g_eglState.display != EGL_NO_DISPLAY) {
        if (g_eglState.surface != EGL_NO_SURFACE && g_eglState.context != EGL_NO_CONTEXT) {
            eglMakeCurrent(g_eglState.display, g_eglState.surface, g_eglState.surface,
                           g_eglState.context);
            if (g_eglState.textureId) {
                glDeleteTextures(1, &g_eglState.textureId);
            }
            if (g_eglState.program) {
                glDeleteProgram(g_eglState.program);
            }
        }
        eglMakeCurrent(g_eglState.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_eglState.surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_eglState.display, g_eglState.surface);
        }
        if (g_eglState.context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_eglState.display, g_eglState.context);
        }
        eglTerminate(g_eglState.display);
    }

    g_eglState.display = EGL_NO_DISPLAY;
    g_eglState.surface = EGL_NO_SURFACE;
    g_eglState.context = EGL_NO_CONTEXT;
    g_eglState.program = 0;
    g_eglState.textureId = 0;
    g_eglState.initialized = false;
}

static bool InitEGL(ANativeWindow *window) {
    DeinitEGL();

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || eglInitialize(display, nullptr, nullptr) == EGL_FALSE) {
        LOGE("InitEGL: eglInitialize failed");
        return false;
    }

    const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) == EGL_FALSE ||
        numConfigs <= 0) {
        LOGE("InitEGL: eglChooseConfig failed");
        eglTerminate(display);
        return false;
    }

    EGLSurface surface = eglCreateWindowSurface(display, config, window, nullptr);
    if (surface == EGL_NO_SURFACE) {
        LOGE("InitEGL: eglCreateWindowSurface failed");
        eglTerminate(display);
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("InitEGL: eglCreateContext failed");
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return false;
    }

    if (eglMakeCurrent(display, surface, surface, context) == EGL_FALSE) {
        LOGE("InitEGL: eglMakeCurrent failed");
        eglDestroyContext(display, context);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return false;
    }

    eglGetNativeClientBufferANDROID = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    eglCreateImageKHR = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
    eglDestroyImageKHR = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
            eglGetProcAddress("eglDestroyImageKHR"));
    glEGLImageTargetTexture2DOES = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));

    GLuint vShader = LoadShader(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint fShader = LoadShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    GLuint program = glCreateProgram();
    glAttachShader(program, vShader);
    glAttachShader(program, fShader);
    glLinkProgram(program);
    glDeleteShader(vShader);
    glDeleteShader(fShader);
    glUseProgram(program);

    GLuint textureId = 0;
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
    glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    g_eglState.display = display;
    g_eglState.surface = surface;
    g_eglState.context = context;
    g_eglState.program = program;
    g_eglState.textureId = textureId;
    g_eglState.initialized = true;

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return true;
}

static void RenderPreview(AHardwareBuffer *hb) {
    if (!g_eglState.initialized || !hb) {
        return;
    }
    if (!eglGetNativeClientBufferANDROID || !eglCreateImageKHR ||
        !eglDestroyImageKHR || !glEGLImageTargetTexture2DOES) {
        return;
    }

    if (eglMakeCurrent(g_eglState.display, g_eglState.surface, g_eglState.surface,
                       g_eglState.context) == EGL_FALSE) {
        LOGE("RenderPreview: eglMakeCurrent failed, error=0x%x", eglGetError());
        return;
    }

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hb);
    EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = eglCreateImageKHR(g_eglState.display, EGL_NO_CONTEXT,
                                          EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("RenderPreview: eglCreateImageKHR failed");
        eglMakeCurrent(g_eglState.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_eglState.textureId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, image);

    glClear(GL_COLOR_BUFFER_BIT);
    GLfloat vertices[] = {-1, 1, -1, -1, 1, 1, 1, -1};
    GLfloat texCoords[] = {0, 0, 0, 1, 1, 0, 1, 1};

    GLint posLoc = glGetAttribLocation(g_eglState.program, "vPosition");
    GLint texLoc = glGetAttribLocation(g_eglState.program, "vTexCoord");

    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(texLoc);
    glVertexAttribPointer(texLoc, 2, GL_FLOAT, GL_FALSE, 0, texCoords);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    eglSwapBuffers(g_eglState.display, g_eglState.surface);

    eglDestroyImageKHR(g_eglState.display, image);
    eglMakeCurrent(g_eglState.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

static void RenderLoop() {
    ANativeWindow *window = nullptr;

    while (g_renderThreadRunning.load(std::memory_order_acquire)) {
        AImage *image = nullptr;
        {
            std::unique_lock<std::mutex> lock(g_renderMutex);
            g_renderCv.wait(lock, [] {
                return !g_renderThreadRunning.load(std::memory_order_acquire) ||
                       !g_renderQueue.empty() || g_pendingWindow != nullptr;
            });

            if (!g_renderThreadRunning.load(std::memory_order_acquire)) {
                break;
            }

            if (g_pendingWindow) {
                if (window) {
                    ANativeWindow_release(window);
                }
                window = g_pendingWindow;
                g_pendingWindow = nullptr;
                InitEGL(window);
            }

            if (!g_renderQueue.empty()) {
                image = g_renderQueue.front();
                g_renderQueue.pop();
            }
        }

        if (image) {
            AHardwareBuffer *hb = nullptr;
            if (AImage_getHardwareBuffer(image, &hb) == AMEDIA_OK && hb) {
                RenderPreview(hb);
            }
            AImage_delete(image);
        }
    }

    DeinitEGL();
    if (window) {
        ANativeWindow_release(window);
    }
}

void SetPreviewSurface(JNIEnv *env, jobject jSurface) {
    std::lock_guard<std::mutex> lock(g_previewMutex);

    if (g_previewSurfaceObj && env && env->IsSameObject(jSurface, g_previewSurfaceObj)) {
        return;
    }

    if (g_renderThreadRunning.load(std::memory_order_acquire)) {
        g_renderThreadRunning.store(false, std::memory_order_release);
        g_renderCv.notify_all();
        if (g_renderThread.joinable()) {
            g_renderThread.join();
        }
    }

    {
        std::lock_guard<std::mutex> queueLock(g_renderMutex);
        DrainPreviewQueueLocked();
        if (g_pendingWindow) {
            ANativeWindow_release(g_pendingWindow);
            g_pendingWindow = nullptr;
        }
    }

    if (g_previewSurfaceObj && env) {
        env->DeleteGlobalRef(g_previewSurfaceObj);
        g_previewSurfaceObj = nullptr;
    }

    if (jSurface && env) {
        g_previewSurfaceObj = env->NewGlobalRef(jSurface);
        ANativeWindow *window = ANativeWindow_fromSurface(env, jSurface);
        if (window) {
            g_renderThreadRunning.store(true, std::memory_order_release);
            {
                std::lock_guard<std::mutex> queueLock(g_renderMutex);
                g_pendingWindow = window;
            }
            g_renderThread = std::thread(RenderLoop);
        } else {
            env->DeleteGlobalRef(g_previewSurfaceObj);
            g_previewSurfaceObj = nullptr;
        }
    }

    g_hasPreview.store(g_renderThreadRunning.load(std::memory_order_acquire),
                       std::memory_order_release);
}

bool IsPreviewEnabled() {
    return g_hasPreview.load(std::memory_order_acquire);
}

bool DispatchPreview(AImage *image) {
    if (!image || !g_renderThreadRunning.load(std::memory_order_acquire)) {
        return false;
    }

    static auto lastDispatchTime = std::chrono::steady_clock::now();
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastDispatchTime).count() <
        16) {
        return false;
    }
    lastDispatchTime = now;

    AImage *imageToDelete = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_renderMutex);
        if (!g_renderThreadRunning.load(std::memory_order_acquire)) {
            return false;
        }
        if (!g_renderQueue.empty()) {
            imageToDelete = g_renderQueue.front();
            g_renderQueue.pop();
        }
        g_renderQueue.push(image);
    }
    if (imageToDelete) {
        AImage_delete(imageToDelete);
    }
    g_renderCv.notify_one();
    return true;
}

void DrainPreviewQueue() {
    std::lock_guard<std::mutex> lock(g_renderMutex);
    DrainPreviewQueueLocked();
}
