package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        private boolean returnEarly;

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Object getResult() {
            return result;
        }
    }

    public static class Unhook {
        public Member getHookedMethod() {
            return null;
        }

        public void unhook() {}
    }
}
