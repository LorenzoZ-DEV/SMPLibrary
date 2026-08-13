package dev.lorenzzz.smplibrary;

import dev.lorenzzz.smplibrary.manager.ManagerService;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public final class Smp_library extends JavaPlugin {

    @Getter
    private static Smp_library instance;
    @Getter
    private ManagerService managers;

    @Override
    public void onLoad() {
        C.logload();
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("storage.yml", false);
        saveResource("messages.yml", false);

        C.info("&aEnabling smp-library...");

        this.managers = new ManagerService(this);
        managers.enable();

        C.line();
        C.logstart();
    }

    @Override
    public void onDisable() {
        if (managers != null) {
            managers.disable();
            managers = null;
        }
        C.logstop();
        instance = null;
    }

    /** Shortcut for the module registry; {@code null} before enable and after disable. */
    public ModuleManager modules() {
        return managers == null ? null : managers.modules();
    }
}
