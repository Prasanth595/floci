package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BridgeGatewayRelayTest {

    @Test
    void runsOnlyOnALinuxHostListeningOnLoopback() {
        assertThat(BridgeGatewayRelay.shouldRun("Linux", false, "127.0.0.1"), is(true));
        assertThat(BridgeGatewayRelay.shouldRun("Linux", false, "localhost"), is(true));
        assertThat(BridgeGatewayRelay.shouldRun("Linux", true, "127.0.0.1"), is(false));
        assertThat(BridgeGatewayRelay.shouldRun("Linux", false, "0.0.0.0"), is(false));
        assertThat(BridgeGatewayRelay.shouldRun("Mac OS X", false, "127.0.0.1"), is(false));
        assertThat(BridgeGatewayRelay.shouldRun("Windows 11", false, "127.0.0.1"), is(false));
    }

    @Test
    void withoutTlsTheHttpPortIsRelayedEvenWhenItDiffersFromTheFlociPort() {
        assertThat(BridgeGatewayRelay.relayPorts(false, 4566, 4570, 443), equalTo(List.of(4570)));
    }

    @Test
    void withTlsTheProxyPortsAreRelayed() {
        assertThat(BridgeGatewayRelay.relayPorts(true, 4566, 4510, 443), equalTo(List.of(4566, 443)));
        assertThat(BridgeGatewayRelay.relayPorts(true, 4566, 4510, 0), equalTo(List.of(4566)));
        assertThat(BridgeGatewayRelay.relayPorts(true, 443, 4510, 443), equalTo(List.of(443)));
    }

    @Test
    void gatewayIsTheFirstIpv4Gateway() {
        assertThat(BridgeGatewayRelay.gatewayOf(network(ipam("fd00::1"), ipam("172.17.0.1"))), equalTo("172.17.0.1"));
    }

    @Test
    void noGatewayWhenTheBridgeHasNone() {
        assertThat(BridgeGatewayRelay.gatewayOf(network(ipam(null))), is(nullValue()));
        assertThat(BridgeGatewayRelay.gatewayOf(null), is(nullValue()));
        assertThat(BridgeGatewayRelay.gatewayOf(mock(Network.class)), is(nullValue()));
    }

    private static Network.Ipam.Config ipam(String gateway) {
        Network.Ipam.Config config = mock(Network.Ipam.Config.class);
        when(config.getGateway()).thenReturn(gateway);
        return config;
    }

    private static Network network(Network.Ipam.Config... configs) {
        Network network = mock(Network.class);
        Network.Ipam ipam = mock(Network.Ipam.class);
        when(network.getIpam()).thenReturn(ipam);
        when(ipam.getConfig()).thenReturn(List.of(configs));
        return network;
    }
}
