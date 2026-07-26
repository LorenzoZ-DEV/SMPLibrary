package dev.lorenzzz.smplibrary.modules;

import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;

/** Feature unit — enabled only if {@code MODULES.<id>.enabled: true} in config.yml. */
public interface Module {

    /** Lower-case id matching the MODULES key in config.yml. */
    String id();

    /** Lifecycle hook — called if enabled. Wire storage/commands/messages here. */
    void onEnable();

    /** Lifecycle hook — called on plugin.shutdown() for active modules. */
    void onDisable();

    Module storage(StorageManager storage);
    Module commands(CommandManager commands);
    Module messages(MessagesManager messages);
}