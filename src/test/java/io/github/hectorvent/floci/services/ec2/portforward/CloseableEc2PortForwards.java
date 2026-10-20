package io.github.hectorvent.floci.services.ec2.portforward;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;

/** Gives tests outside this package ownership of the real forwarding executor's cleanup. */
public final class CloseableEc2PortForwards extends Ec2PortForwardManager implements AutoCloseable {
    public CloseableEc2PortForwards(DockerClient docker, ContainerBuilder builder,
                                   ContainerLifecycleManager lifecycle, PortAllocator ports, EmulatorConfig config) {
        super(docker, builder, lifecycle, ports, config);
    }

    @Override
    public void close() {
        stop();
    }
}
