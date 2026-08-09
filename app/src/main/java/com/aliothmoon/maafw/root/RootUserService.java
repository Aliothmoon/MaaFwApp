package com.aliothmoon.maafw.root;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.IBinder;

import com.aliothmoon.maafw.third.Ln;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RootUserService {

    private static final String TAG = "RootUserService";

    private RootUserService() {
    }

    public static CreatedService create(String[] args) {
        ParsedArgs parsed = ParsedArgs.parse(args);
        if (parsed == null) {
            return null;
        }

        int userId = parsed.uid / 100000;
        String appName = parsed.debugName != null ? parsed.debugName : parsed.packageName + ":root_service";

        Ln.i(String.format("%s: starting service %s/%s...", TAG, parsed.packageName, parsed.className));
        try {
            Object activityThread = createActivityThread();
            Context systemContext = getSystemContext(activityThread);
            if (systemContext == null) {
                throw new IllegalStateException("system context is null");
            }

            setAppName(appName, userId);

            Context packageContext = createPackageContextAsUser(systemContext, parsed.packageName, userId);
            Application application = null;
            try {
                application = makeApplication(activityThread, packageContext);
            } catch (Throwable tr) {
                // 部分 OEM（如 MIUI）修改了 LoadedApk.makeApplication，在 shell 身份下
                // 因 theme/系统文件缺失导致初始化失败；packageContext 本身可用，直接降级
                Ln.w(TAG + ": makeApplication failed, falling back to packageContext", tr);
            }
            Context constructorContext = application != null ? application : packageContext;
            ClassLoader classLoader = constructorContext.getClassLoader();
            Class<?> serviceClass = classLoader.loadClass(parsed.className);
            IBinder service = instantiateService(serviceClass, constructorContext);

            return new CreatedService(service, parsed.token, parsed.packageName, userId);
        } catch (Throwable tr) {
            Ln.e(String.format("%s: unable to start service %s/%s", TAG, parsed.packageName, parsed.className), tr);
            return null;
        }
    }

    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private static Object createActivityThread() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        return systemMain.invoke(null);
    }

    private static Context getSystemContext(Object activityThread) throws Exception {
        Method method = activityThread.getClass().getDeclaredMethod("getSystemContext");
        method.setAccessible(true);
        return (Context) method.invoke(activityThread);
    }

    private static Application makeApplication(Object activityThread, Context packageContext) throws Exception {
        Field packageInfoField = packageContext.getClass().getDeclaredField("mPackageInfo");
        packageInfoField.setAccessible(true);
        Object loadedApk = packageInfoField.get(packageContext);

        Method makeApplication = loadedApk.getClass()
                .getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
        makeApplication.setAccessible(true);
        Application application = (Application) makeApplication.invoke(loadedApk, true, null);

        Field initialApplicationField = activityThread.getClass().getDeclaredField("mInitialApplication");
        initialApplicationField.setAccessible(true);
        initialApplicationField.set(activityThread, application);
        return application;
    }

    @SuppressWarnings({"JavaReflectionMemberAccess", "JavaReflectionInvocation"})
    private static Context createPackageContextAsUser(Context context, String packageName, int userId)
            throws Exception {
        int flags = Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY;
        try {
            Class<?> userHandleClass = Class.forName("android.os.UserHandle");
            Object userHandle;
            try {
                Method ofMethod = userHandleClass.getDeclaredMethod("of", int.class);
                userHandle = ofMethod.invoke(null, userId);
            } catch (Throwable ignored) {
                Constructor<?> constructor = userHandleClass.getDeclaredConstructor(int.class);
                constructor.setAccessible(true);
                userHandle = constructor.newInstance(userId);
            }

            Method createMethod = Context.class.getMethod(
                    "createPackageContextAsUser",
                    String.class,
                    int.class,
                    userHandleClass
            );
            return (Context) createMethod.invoke(context, packageName, flags, userHandle);
        } catch (Throwable ignored) {
            return context.createPackageContext(packageName, flags);
        }
    }

    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private static void setAppName(String name, int userId) {
        try {
            Class<?> cls = Class.forName("android.ddm.DdmHandleAppName");
            Method method = cls.getDeclaredMethod("setAppName", String.class, int.class);
            method.invoke(null, name, userId);
        } catch (Throwable tr) {
            Ln.w(TAG + ": setAppName failed", tr);
        }
    }

    private static IBinder instantiateService(Class<?> serviceClass, Context context) throws Exception {
        try {
            Constructor<?> constructor = serviceClass.getConstructor(Context.class);
            return (IBinder) constructor.newInstance(context);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> constructor = serviceClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (IBinder) constructor.newInstance();
        }
    }

    public record CreatedService(IBinder service, String token, String packageName, int userId) {
    }

    private record ParsedArgs(String token, String packageName, String className, int uid,
                              String debugName) {

        private static ParsedArgs parse(String[] args) {
            String token = null;
            String packageName = null;
            String className = null;
            String debugName = null;
            int uid = -1;

            for (String arg : args) {
                if (arg.startsWith("--token=")) {
                    token = arg.substring(8);
                } else if (arg.startsWith("--package=")) {
                    packageName = arg.substring(10);
                } else if (arg.startsWith("--class=")) {
                    className = arg.substring(8);
                } else if (arg.startsWith("--uid=")) {
                    uid = Integer.parseInt(arg.substring(6));
                } else if (arg.startsWith("--debug-name=")) {
                    debugName = arg.substring(13);
                }
            }

            if (token == null || packageName == null || className == null || uid < 0) {
                Ln.e(TAG + ": missing required args");
                return null;
            }
            return new ParsedArgs(token, packageName, className, uid, debugName);
        }
    }
}
