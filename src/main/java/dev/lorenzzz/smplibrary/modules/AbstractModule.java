package dev.lorenzzz.smplibrary.modules;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.DialogGuard;
import dev.lorenzzz.smplibrary.manager.EconomyManager;
import dev.lorenzzz.smplibrary.manager.ManagerService;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.manager.ToggleManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base for every module: holds the injected managers, resolves the module's own config
 * section, and owns the listeners and scheduler tasks it registers so they are always
 * released on reload / shutdown.
 *
 * <p>{@link #onDisable()} is final on purpose — a module that forgot to unregister its
 * listener leaked a handler on every reload. Subclasses override {@link #onShutdown()}
 * for their own cleanup and get the teardown for free.
 */
public abstract class AbstractModule implements Module {

    protected final Smp_library plugin;

    // Thread-safe on purpose: a module may cancel one of its own tasks from an async
    // event handler (chat games does exactly that when a player wins) while the main
    // thread is still registering others.
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Integer> taskIds = new CopyOnWriteArrayList<>();

    protected ManagerService managers;
    protected StorageManager storage;
    protected CommandManager commands;
    protected MessagesManager messages;

    protected AbstractModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public final void bind(ManagerService managers) {
        this.managers = managers;
        this.storage = managers.storage();
        this.commands = managers.commands();
        this.messages = managers.messages();
    }

    protected EconomyManager economy() {
        return managers == null ? null : managers.economy();
    }

    /** The plugin-wide per-player toggle store; {@code null} only before {@link #bind}. */
    protected ToggleManager toggles() {
        return managers == null ? null : managers.toggles();
    }

    /** The plugin-wide dialog guard; {@code null} only before {@link #bind}. */
    protected DialogGuard dialogGuard() {
        return managers == null ? null : managers.dialogGuard();
    }

    /** This module's {@code MODULES.<id>} section, or {@code null} when unconfigured. */
    protected ConfigurationSection config() {
        return plugin.getConfig().getConfigurationSection("MODULES." + id());
    }

    // ------------------------------------------------------------- lifecycle

    /** Registers a Bukkit listener; unregistered automatically on disable. */
    protected void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /** Schedules a repeating task; cancelled automatically on disable. */
    protected int repeat(Runnable task, long delayTicks, long periodTicks) {
        int id = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks).getTaskId();
        taskIds.add(id);
        return id;
    }

    /**
     * Schedules a one-shot task; cancelled automatically on disable if it hasn't fired.
     * The bookkeeping entry drops itself once the task runs, so a module that schedules
     * one of these per round doesn't accumulate dead ids for the life of the server.
     */
    protected int later(Runnable task, long delayTicks) {
        int[] id = new int[1];
        id[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            taskIds.remove(Integer.valueOf(id[0]));
            task.run();
        }, delayTicks).getTaskId();
        taskIds.add(id[0]);
        return id[0];
    }

    protected void cancelTask(int taskId) {
        if (taskId == -1) return;
        Bukkit.getScheduler().cancelTask(taskId);
        taskIds.remove(Integer.valueOf(taskId));
    }

    @Override
    public final void onDisable() {
        try {
            onShutdown();
        } finally {
            listeners.forEach(HandlerList::unregisterAll);
            listeners.clear();
            taskIds.forEach(Bukkit.getScheduler()::cancelTask);
            taskIds.clear();
        }
    }

    /** Module-specific teardown. Listeners and scheduled tasks are released for you. */
    protected void onShutdown() {}
}
