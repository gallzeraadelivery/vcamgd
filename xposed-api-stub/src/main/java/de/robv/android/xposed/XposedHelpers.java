package de.robv.android.xposed;

import java.lang.reflect.Method;
import java.util.Set;

public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new IllegalStateException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz,
            String methodName,
            Object... parameterTypesAndCallback) {
        throw new IllegalStateException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback) {
        throw new IllegalStateException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new IllegalStateException("stub");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new IllegalStateException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new IllegalStateException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new IllegalStateException("stub");
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        throw new IllegalStateException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new IllegalStateException("stub");
    }
}
