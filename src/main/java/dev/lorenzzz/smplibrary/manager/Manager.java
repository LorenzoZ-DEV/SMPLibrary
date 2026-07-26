package dev.lorenzzz.smplibrary.manager;

/** Lifecycle unit owned by {@link ManagerService}. Order is managed by the service. */
public interface Manager {

    /** Stable lower-case id used for logging. */
    String name();

    /** Start up. Called by ManagerService in registration order. */
    void enable();

    /** Tear down. Called by ManagerService in reverse order. */
    void disable();
}