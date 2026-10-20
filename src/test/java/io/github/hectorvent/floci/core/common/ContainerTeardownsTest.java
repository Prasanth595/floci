package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerTeardownsTest {
    @Test
    void processShutdownSelectsItsHookWhileResetKeepsDrainingAndLegacyServicesKeepTheirDefault() {
        List<String> calls = new ArrayList<>();
        ContainerTeardown legacy = () -> calls.add("legacy");
        ContainerTeardown bounded = new ContainerTeardown() {
            @Override
            public void stopManagedContainers() { calls.add("drain"); }
            @Override
            public void stopManagedContainersForShutdown() { calls.add("bounded"); }
        };
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> beans = mock(Instance.class);
        when(beans.iterator()).thenAnswer(call -> List.of(legacy, bounded).iterator());
        ContainerTeardowns.stopAllForShutdown(beans, Logger.getLogger(getClass()));
        assertEquals(List.of("legacy", "bounded"), calls);
        calls.clear();
        ContainerTeardowns.stopAll(beans, Logger.getLogger(getClass()));
        assertEquals(List.of("legacy", "drain"), calls);
    }

    @Test
    void aFailedProcessHookDoesNotSkipOtherTeardown() {
        List<String> calls = new ArrayList<>();
        ContainerTeardown failed = () -> { throw new IllegalStateException("controlled teardown refusal"); };
        ContainerTeardown succeeding = () -> calls.add("next");
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> beans = mock(Instance.class);
        when(beans.iterator()).thenAnswer(call -> List.of(failed, succeeding).iterator());
        ContainerTeardowns.stopAllForShutdown(beans, Logger.getLogger(getClass()));
        assertEquals(List.of("next"), calls);
    }
}
