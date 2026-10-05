package org.lsposed.lspatch.service;

import android.content.Context;

import org.lsposed.lspatch.share.remote.LSPatchModuleService;
import org.lsposed.lspatch.share.remote.LSPatchXposedService;
import org.lsposed.lspatch.share.remote.FrameworkInfo;
import org.lsposed.lspatch.share.remote.HotReloadDriver;
import org.lsposed.lspatch.share.remote.PreferenceChangeNotifier;
import org.lsposed.lspatch.share.remote.RemoteFileStore;
import org.lsposed.lspatch.share.remote.RemotePreferenceStore;
import org.lsposed.lspatch.share.remote.ScopeSource;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The embedded-mode wiring of the shared remote-service layer, for one host process.
 *
 * <p>The two stubs a module gets — its {@link LSPatchModuleService} on {@code LoadedModule.service}
 * (built in {@code LocalApplicationService}) and its in-process {@link LSPatchXposedService} (handed
 * to the hook in the delivery loop) — must share one store and one change notifier, or a write on one
 * would not reach a subscriber on the other. This holds the process-global stores and a per-module
 * notifier so both construction sites agree. Manager mode has its own analogous wiring backed by the
 * manager's storage.</p>
 */
public final class EmbeddedRemoteServices {

    private static volatile EmbeddedRemoteServices instance;

    private final RemotePreferenceStore prefs;
    private final RemoteFileStore files;
    private final Map<String, PreferenceChangeNotifier> notifiers = new ConcurrentHashMap<>();

    private EmbeddedRemoteServices(Context context) {
        this.prefs = new RemotePreferenceStore(context);
        this.files = new RemoteFileStore(context);
    }

    public static EmbeddedRemoteServices get(Context context) {
        EmbeddedRemoteServices local = instance;
        if (local == null) {
            synchronized (EmbeddedRemoteServices.class) {
                local = instance;
                if (local == null) {
                    // Not getApplicationContext(): this runs during app bootstrap, before the
                    // Application exists, so getApplicationContext() is null here. The bootstrap
                    // context is a valid Context for the app's database and files.
                    local = new EmbeddedRemoteServices(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    private PreferenceChangeNotifier notifier(String modulePackageName) {
        return notifiers.computeIfAbsent(modulePackageName, p -> new PreferenceChangeNotifier());
    }

    /** The read service that rides on {@code LoadedModule.service}. */
    public LSPatchModuleService moduleService(String modulePackageName, long properties) {
        return new LSPatchModuleService(modulePackageName, properties, prefs, files, notifier(modulePackageName));
    }

    /** The full service handed to the hook in-process (the write path for a companion-less module). */
    public LSPatchXposedService xposedService(String modulePackageName, FrameworkInfo info, String hostPackageName) {
        ScopeSource scope = mp -> Collections.singletonList(hostPackageName);
        return new LSPatchXposedService(modulePackageName, info, prefs, files,
                notifier(modulePackageName), scope, HotReloadDriver.UNSUPPORTED);
    }
}
