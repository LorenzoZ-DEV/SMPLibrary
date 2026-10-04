package dev.lorenzzz.smplibrary;

import dev.lorenzzz.smplibrary.manager.ConfigUpdater;
import dev.lorenzzz.smplibrary.manager.ManagerService;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.util.Banner;
import dev.lorenzzz.smplibrary.util.Log;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public final class Smp_library extends JavaPlugin {

    @Getter
    private static Smp_library instance;
    @Getter
    private ManagerService managers;

    @Override
    public void onLoad() {
        Log.init(getLogger());
        Banner.loading();
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("storage.yml", false);
        saveResource("messages.yml", false);

        // saveDefaultConfig() only writes when the file is absent, so an existing install never
        // sees options added by a later build. Merge them in before any module reads settings.
        ConfigUpdater.update(this);

        Log.info("&aEnabling smp-library...");

        this.managers = new ManagerService(this);
        managers.enable();

        Banner.enabled();
    }

    @Override
    public void onDisable() {
        if (managers != null) {
            managers.disable();
            managers = null;
        }
        Banner.disabled();
        instance = null;
    }

    /** Shortcut for the module registry; {@code null} before enable and after disable. */
    public ModuleManager modules() {
        return managers == null ? null : managers.modules();
    }
}
