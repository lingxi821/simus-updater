-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
 public static void check*(...);
 public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}

# A saved back stack names its entries by class, and the platform hands that state back to an
# activity it recreates -- including after an update, which is exactly when a shrinker will have
# renamed the class it names. Keeping the route names makes a restored stack readable by the build
# that receives it rather than only by the one that wrote it.
-keepnames class org.lsposed.lspatch.ui.navigation.** { *; }

# The navigation routes are serialised by kotlinx.serialization, which reaches a class's generated
# serializer through the companion it was compiled onto. Neither is called from anywhere the
# shrinker can see.
-keepclassmembers class **$$serializer { *** descriptor; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class com.beust.jcommander.** { *; }
# The Shizuku user service is loaded and instantiated by name in a separate app_process (shell uid)
# by Shizuku's starter, and its methods are reached only over a binder -- neither of which R8 can
# see, because the class is not a manifest component and nothing in-app constructs it. Without these
# it is renamed or stripped in release, and the shell service never starts: dexopt, silent installs
# and the whole log collector go dead while Shizuku still reads "granted". Keep the service and the
# generated AIDL (interface, Stub, Proxy) it dispatches through.
-keep class org.lsposed.lspatch.ShizukuService { *; }
-keep class org.lsposed.lspatch.IShizukuService** { *; }

# The framework IPC surface crosses a binder to the patched app, whose loader is not obfuscated.
-keep class org.matrix.vector.ipc.** { *; }
-keep class org.lsposed.lspatch.database.** { *; }
-keep class org.lsposed.lspatch.Patcher$Options { *; }
-keep class org.lsposed.lspatch.share.LSPConfig { *; }
-keep class org.lsposed.lspatch.share.PatchConfig { *; }

# Reflective JSON is a contract written in field names and generic signatures, and the shrinker
# honours neither for a class nothing else pins down: it renames the fields the keys are made of,
# drops the element type of a collection -- leaving a raw list that deserialises into maps -- and
# removes anything it can prove is never read. These models are persisted and read back, so their
# shape is API.
-keep class org.lsposed.lspatch.data.model.** { *; }
-keepclassmembers class org.lsposed.patch.LSPatch {
    private <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
