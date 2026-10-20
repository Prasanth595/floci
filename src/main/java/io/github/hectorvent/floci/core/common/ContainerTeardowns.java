package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

/**
 * Runs every {@link ContainerTeardown} implementation, tolerating a failure in one so it
 * doesn't block the others or whatever cleanup the caller runs next. {@code EmulatorLifecycle}
 * (on {@code ShutdownEvent}) and {@code EmulatorInfoController} (on {@code /state/reset} and
 * {@code /state/nuke}) each independently ran this identical loop before this class existed.
 */
public final class ContainerTeardowns {

    private ContainerTeardowns() {
    }

    public static void stopAll(Instance<ContainerTeardown> teardowns, Logger log) {
        stopAll(teardowns, log, false);
    }

    public static void stopAllForShutdown(Instance<ContainerTeardown> teardowns, Logger log) {
        stopAll(teardowns, log, true);
    }

    private static void stopAll(Instance<ContainerTeardown> teardowns, Logger log, boolean shutdown) {
        for (ContainerTeardown teardown : teardowns) {
            try {
                if (shutdown) {
                    teardown.stopManagedContainersForShutdown();
                } else {
                    teardown.stopManagedContainers();
                }
            } catch (Exception e) {
                log.warnv("Container teardown failed for {0}: {1}",
                        teardown.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
