package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.NetworkExposureGuard;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Lets containers reach a Floci that runs directly on a Linux host and listens only on loopback.
 *
 * <p>Containers Floci starts (Lambda, ECS, CodeBuild, ...) call back through
 * {@code host.docker.internal}, which on Linux maps to the Docker bridge gateway
 * ({@code host-gateway}, normally {@code 172.17.0.1}). Docker Desktop forwards that address to the
 * host's loopback; native Linux Docker does not, so a Floci bound to {@code 127.0.0.1} refuses
 * those connections. This relay listens on the bridge gateway address, which belongs to the host
 * but is not routed from the LAN, and pipes each connection to Floci's own port on loopback. It
 * carries raw TCP, so plain HTTP and the TLS proxy both pass through.
 *
 * <p>It starts only on a Linux host (not inside a container, where containers reach Floci by its
 * own address), only while the public listener is on loopback (a wider bind already covers the
 * gateway), and only when Docker answers.
 */
@ApplicationScoped
public class BridgeGatewayRelay {

    private static final Logger LOG = Logger.getLogger(BridgeGatewayRelay.class);

    static final String DEFAULT_BRIDGE = "bridge";

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final Config mpConfig;
    private final ContainerDetector containerDetector;
    private final ContainerLifecycleManager lifecycleManager;

    private volatile NetServer server;
    private volatile NetClient client;

    @Inject
    public BridgeGatewayRelay(Vertx vertx, EmulatorConfig config, Config mpConfig,
                              ContainerDetector containerDetector, ContainerLifecycleManager lifecycleManager) {
        this.vertx = vertx;
        this.config = config;
        this.mpConfig = mpConfig;
        this.containerDetector = containerDetector;
        this.lifecycleManager = lifecycleManager;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!shouldRun(System.getProperty("os.name", ""), containerDetector.isRunningInContainer(),
                NetworkExposureGuard.publicHost(mpConfig))) {
            return;
        }
        // Inspecting the network is a Docker call; keep it off the startup path.
        Thread.ofVirtual().name("floci-bridge-gateway-relay").start(this::start);
    }

    static boolean shouldRun(String osName, boolean inContainer, String publicHost) {
        return osName.toLowerCase(Locale.ROOT).contains("linux")
                && !inContainer
                && NetworkExposureGuard.isLoopback(publicHost);
    }

    void start() {
        String gateway;
        try {
            Network bridge = lifecycleManager.getDockerClient().inspectNetworkCmd()
                    .withNetworkId(DEFAULT_BRIDGE).exec();
            gateway = gatewayOf(bridge);
        } catch (RuntimeException e) {
            LOG.debugv("Bridge gateway relay not started: Docker is not reachable ({0})", e.getMessage());
            return;
        }
        if (gateway == null) {
            LOG.warn("Bridge gateway relay not started: the default bridge network has no IPv4 gateway;"
                    + " containers may not reach Floci on loopback");
            return;
        }
        int port = config.port();
        NetClient netClient = vertx.createNetClient();
        NetServer netServer = vertx.createNetServer();
        netServer.connectHandler(front -> relay(front, netClient, port));
        netServer.listen(port, gateway).onComplete(ar -> {
            if (ar.succeeded()) {
                client = netClient;
                server = netServer;
                LOG.infov("Containers reach Floci through {0}:{1}, relayed to 127.0.0.1:{1}", gateway,
                        String.valueOf(port));
            } else {
                netClient.close();
                LOG.warnv("Could not listen on the Docker bridge gateway {0}:{1} ({2}); containers will not"
                                + " reach Floci on loopback. Set QUARKUS_HTTP_HOST to an address they can reach"
                                + " (with FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE=true) instead.",
                        gateway, String.valueOf(port), ar.cause().getMessage());
            }
        });
    }

    private static void relay(NetSocket front, NetClient netClient, int port) {
        front.pause();
        netClient.connect(port, "127.0.0.1").onComplete(ar -> {
            if (ar.failed()) {
                LOG.debugv("Bridge gateway relay: could not reach 127.0.0.1:{0}: {1}", String.valueOf(port),
                        ar.cause().getMessage());
                front.close();
                return;
            }
            NetSocket back = ar.result();
            front.pipeTo(back).onFailure(err ->
                    LOG.debugv("Bridge gateway relay: pipe to Floci failed: {0}", err.getMessage()));
            back.pipeTo(front).onFailure(err ->
                    LOG.debugv("Bridge gateway relay: pipe to container failed: {0}", err.getMessage()));
        });
    }

    /** The default bridge's IPv4 gateway, or {@code null} when it has none. */
    static String gatewayOf(Network bridge) {
        if (bridge == null || bridge.getIpam() == null || bridge.getIpam().getConfig() == null) {
            return null;
        }
        List<Network.Ipam.Config> configs = bridge.getIpam().getConfig();
        for (Network.Ipam.Config ipam : configs) {
            String gateway = ipam.getGateway();
            if (gateway != null && !gateway.isBlank() && !gateway.contains(":")) {
                return gateway;
            }
        }
        return null;
    }

    void onStop(@Observes ShutdownEvent ignored) {
        NetServer current = server;
        if (current != null) {
            current.close();
        }
        NetClient currentClient = client;
        if (currentClient != null) {
            currentClient.close();
        }
    }
}
