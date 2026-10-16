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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets containers reach a Floci that runs directly on a Linux host and listens only on loopback.
 *
 * <p>Containers Floci starts (Lambda, ECS, CodeBuild, ...) call back through
 * {@code host.docker.internal}, which on Linux maps to the Docker bridge gateway
 * ({@code host-gateway}, normally {@code 172.17.0.1}). Docker Desktop forwards that address to the
 * host's loopback; native Linux Docker does not, so a Floci bound to {@code 127.0.0.1} refuses
 * those connections. This relay listens on the bridge gateway address, which belongs to the host
 * but is not routed from the LAN, and pipes each connection to the same port on the loopback address
 * Floci listens on. It carries raw TCP, so plain HTTP and the TLS proxy both pass through.
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

    private final List<NetServer> servers = new CopyOnWriteArrayList<>();
    private volatile NetClient client;
    private volatile boolean stopped;

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

    /**
     * The ports containers call Floci on, the same ones {@code ContainerReachableEndpoint}
     * advertises: with TLS, the proxy's public port plus its AWS-HTTPS port; without it, the HTTP
     * server's port, which can differ from {@code floci.port}.
     */
    static List<Integer> relayPorts(boolean tlsEnabled, int flociPort, int httpPort, int awsHttpsPort) {
        if (!tlsEnabled) {
            return List.of(httpPort);
        }
        if (awsHttpsPort > 0 && awsHttpsPort != flociPort) {
            return List.of(flociPort, awsHttpsPort);
        }
        return List.of(flociPort);
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
        String target = NetworkExposureGuard.publicHost(mpConfig);
        List<Integer> ports = relayPorts(config.tls().enabled(), config.port(),
                mpConfig.getValue("quarkus.http.port", Integer.class), config.tls().awsHttpsPort());
        NetClient netClient = vertx.createNetClient();
        client = netClient;
        if (stopped) {
            onStop(null);
            return;
        }
        for (int port : ports) {
            listen(gateway, port, target, netClient, port == ports.getFirst());
        }
    }

    private void listen(String gateway, int port, String target, NetClient netClient, boolean primary) {
        NetServer netServer = vertx.createNetServer();
        netServer.connectHandler(front -> relay(front, netClient, target, port));
        servers.add(netServer);
        netServer.listen(port, gateway).onComplete(ar -> {
            if (stopped) {
                netServer.close();
            } else if (ar.succeeded()) {
                LOG.infov("Containers reach Floci through {0}:{1}, relayed to {2}:{1}", gateway,
                        String.valueOf(port), target);
            } else if (primary) {
                LOG.warnv("Could not listen on the Docker bridge gateway {0}:{1} ({2}); containers will not"
                                + " reach Floci on loopback. Set QUARKUS_HTTP_HOST to an address they can reach"
                                + " (with FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE=true) instead.",
                        gateway, String.valueOf(port), ar.cause().getMessage());
            } else {
                // The AWS-HTTPS port (443 by default) is privileged, like the TLS proxy's own bind.
                LOG.debugv("Bridge gateway relay: could not listen on {0}:{1} ({2})", gateway,
                        String.valueOf(port), ar.cause().getMessage());
            }
        });
    }

    private static void relay(NetSocket front, NetClient netClient, String target, int port) {
        front.pause();
        netClient.connect(port, target).onComplete(ar -> {
            if (ar.failed()) {
                LOG.debugv("Bridge gateway relay: could not reach {0}:{1}: {2}", target, String.valueOf(port),
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
        // A startup still inspecting the network or binding checks this flag and closes what it opens.
        stopped = true;
        for (NetServer server : servers) {
            server.close();
        }
        NetClient currentClient = client;
        if (currentClient != null) {
            currentClient.close();
        }
    }
}
