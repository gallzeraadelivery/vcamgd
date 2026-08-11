package de.robv.android.xposed;

import java.lang.reflect.Member;

public class XposedBridge {
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        return null;
    }

    public static void log(String text) {}
    public static void log(Throwable t) {}
}
