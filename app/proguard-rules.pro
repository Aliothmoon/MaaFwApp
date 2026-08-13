# R8 规则
#
# 这个应用有三类东西 R8 的可达性分析看不见，改名或裁掉都只在设备上炸，编译期一声不吭：
#   1. native 按字面名 FindClass / GetStaticMethodID 的 upcall 目标
#   2. JNA 按 Java 方法名去 dlsym 的 C 函数绑定
#   3. 特权进程用 app_process 按类名加载的入口（与 app 不是同一个进程）
# 每一条都注明是哪一种，删之前先确认对应的调用方也没了

# 崩溃日志要能对得上号：CrashHandler 落盘的栈是给人看的，行号丢了就只剩类名
# 出包后记得留 build/outputs/mapping/<variant>/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ── 1. native upcall ──
# bridge.cpp 里 kNativeBridgeClass / kDriverClass 是字面量，
# bridge_input.cpp 按 "touchDown" "(III)Z" 这类签名取 methodID
-keep class com.aliothmoon.maafw.bridge.NativeBridgeLib { *; }
-keep class com.aliothmoon.maafw.bridge.DriverClass { *; }

# ── 2. JNA ──
# Native.load(name, X::class.java) 拿 Java 方法名当 C 符号名去 dlsym，
# 整包保留而不是逐个接口：MaaEventCallback 这类回调是嵌套类型，-keep interface 盖不到
-keep class com.aliothmoon.maafw.maa.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**

# ── 3. 特权进程的入口 ──
# app_process --starter-class / --class 按名字加载；Shizuku 那条走 ComponentName
-keep class com.aliothmoon.maafw.remote.RemoteServiceImpl { *; }
-keep class com.aliothmoon.maafw.remote.LogcatCaptureServiceImpl { *; }
-keep class com.aliothmoon.maafw.root.** { *; }
# 隐藏 API 的反射壳；反射目标是 framework，但这条路只在特权进程里跑，不值得赌
-keep class com.aliothmoon.maafw.third.** { *; }

# AIDL：app 与特权进程各跑一份同样的 dex，descriptor 是字面量，
# 但 Stub/Proxy 被裁掉过一次就再也连不上，成本低于风险
-keep class com.aliothmoon.maafw.RemoteService** { *; }
-keep class com.aliothmoon.maafw.IMaaRunnerCallback** { *; }
-keep class com.aliothmoon.maafw.ITouchEventCallback** { *; }
-keep class com.aliothmoon.maafw.ILogcatService** { *; }

# hidden-api 是 compileOnly，运行时由 framework 提供，包里没有
-dontwarn android.**
-dontwarn com.android.internal.**

# ── kotlinx.serialization ──
# 生成的 $$serializer 与 Companion.serializer() 没有静态调用点
-keepclassmembers class com.aliothmoon.maafw.** {
    *** Companion;
}
-keepclasseswithmembers class com.aliothmoon.maafw.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aliothmoon.maafw.**$$serializer { *; }

# ── 落盘的 enum 常量名 ──
# AppSettings 以 name 存进 DataStore，回读走 valueOf；RunLogKind 按 name 进会话日志文件。
# 改名不会报错，只会让 valueOf 抛异常后静默回落到默认值——用户的设置一次性全丢
-keepclassmembers enum com.aliothmoon.maafw.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# markwon-image 对 SVG 与 GIF 的支持是可选依赖，本仓库没引，那两条解码分支进不去
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**

# ── SMTP 推送渠道 ──
# jakarta.mail 靠 META-INF/services 与反射挑实现，裁掉表现为运行时 NoSuchProviderException
-keep class org.eclipse.angus.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn org.eclipse.angus.**
-dontwarn jakarta.**
-dontwarn javax.**
