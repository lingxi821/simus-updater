package de.robv.android.xposed;
/** 仅用于编译。 */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; }
    }

    public class Unhook {
        public void unhook() {}
    }
}
