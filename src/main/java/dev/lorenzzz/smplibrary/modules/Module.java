package dev.lorenzzz.smplibrary.modules;

import dev.lorenzzz.smplibrary.manager.ManagerService;

import java.util.Map;

/** Feature unit — enabled only if {@code MODULES.<id>.enabled: true} in config.yml. */
public interface Module {

    /** Lower-case id matching the MODULES key in config.yml. */
    String id();

    /** Injects the shared managers. Always called by the ModuleManager before {@link #onEnable()}. */
    void bind(ManagerService managers);

    /** Lifecycle hook — called if enabled. */
    void onEnable();

    /** Lifecycle hook — called for every running module on reload and on plugin shutdown. */
    void onDisable();

    /**
     * Default config values for this module, used when no MODULES.&lt;id&gt; section exists.
     * Every module must at minimum define {@code enabled: true}.
     */
    default Map<String, Object> defaults() {
        return Map.of("enabled", true);
    }
}
