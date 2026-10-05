package io.nidome.simspoof;

import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * TikTok 美区模块。
 *
 * 只做一件事：把 SIM / 网络的国家码伪装成美国，运营商伪装成 T-Mobile (310260)。
 *
 * 刻意**不碰** TikTok 自己的适配逻辑（屏幕方向、平板判定、布局策略都保持官方原版）——
 * 之前为「平板横屏」加的 isTablet / 方向解锁 hook 已移除（实测那台 vivo 平板是系统侧
 * 按包名强制小窗，App 侧怎么改都没用）。
 *
 * 注意：钩子写法沿用最初能跑通的版本 —— XposedHelpers.findMethodExact + XposedBridge.hookMethod。
 * （LSPatch 的 legacy 桥对 findAndHookMethod 支持不完整，用它会静默失效。）
 */
public class SpoofModule implements IXposedHookLoadPackage {

    private static final String TAG = "SIMSPOOF";
    private static final String PKG = "com.zhiliaoapp.musically";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return;
        if (!lp.packageName.startsWith(PKG)) return;
        Log.i(TAG, "TikTok process hit: " + lp.packageName + " , injecting");

        hook(cl(lp), "android.telephony.TelephonyManager", "getSimCountryIso", "us");
        hook(cl(lp), "android.telephony.TelephonyManager", "getNetworkCountryIso", "us");
        hook(cl(lp), "android.telephony.TelephonyManager", "getSimOperator", "310260");
        hook(cl(lp), "android.telephony.TelephonyManager", "getNetworkOperator", "310260");
        hook(cl(lp), "android.telephony.TelephonyManager", "getSimOperatorName", "T-Mobile");
        hook(cl(lp), "android.telephony.TelephonyManager", "getNetworkOperatorName", "T-Mobile");
    }

    private static ClassLoader cl(XC_LoadPackage.LoadPackageParam lp) {
        return lp.classLoader != null ? lp.classLoader : SpoofModule.class.getClassLoader();
    }

    /** 让某个无参方法固定返回字符串。 */
    private void hook(ClassLoader loader, String cls, String method, final String value) {
        try {
            Method m = XposedHelpers.findMethodExact(Class.forName(cls, false, loader), method,
                    new Object[0]);
            if (m == null) {
                Log.w(TAG, "method not found: " + cls + "#" + method);
                return;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            });
            Log.i(TAG, "hooked " + cls + "#" + method + " -> " + value);
        } catch (Throwable t) {
            Log.w(TAG, "hook failed " + cls + "#" + method + " : " + t);
        }
    }
}
