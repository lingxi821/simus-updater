package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
/** 仅用于编译：运行时由 LSPatch loader 提供真身。 */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
