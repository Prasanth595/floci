package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ApsWorkspaceReceiptTest {

    private static final String ACCOUNT = "741648800003";
    private static final String REGION = "eu-central-1";
    private final AccountAwareStorageBackend<PrometheusWorkspace> workspaces =
            spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
    private final AccountAwareStorageBackend<PrometheusWorkspace> receipts = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final StorageFactory factory = mock(StorageFactory.class);
    private final RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HttpHeaders headers = mock(HttpHeaders.class);

    @BeforeEach
    void setUp() {
        when(factory.<PrometheusWorkspace>create(eq("aps"), eq("aps-workspaces.json"), any())).thenReturn(workspaces);
        when(factory.<PrometheusWorkspace>create(eq("aps"), eq("aps-workspace-requests.json"), any())).thenReturn(receipts);
    }

    @Test
    void tokenReceiptSurvivesJsonRoundTripAndServiceRecreation() throws Exception {
        String token = "receipt-reload";
        Map<String, Object> request = Map.of("alias", "receipt", "clientToken", token, "tags", Map.of("team", "one"));
        ApsController first = new ApsController(new ApsService(factory, resolver), resolver, mapper);
        JsonNode initial = (JsonNode) first.createWorkspace(headers, request).getEntity();
        PrometheusWorkspace receipt = receipts.get(REGION + "::" + token).orElse(null);
        assertNotNull(receipt, "the receipt belongs in StorageFactory storage, not a service-local cache");
        receipts.put(REGION + "::" + token, mapper.readValue(mapper.writeValueAsBytes(receipt), PrometheusWorkspace.class));
        ApsController recreated = new ApsController(new ApsService(factory, resolver), resolver, mapper);
        JsonNode replay = (JsonNode) recreated.createWorkspace(headers, request).getEntity();
        assertEquals(initial, replay);
        assertEquals(1, workspaces.scan(key -> true).size());
    }

    @Test
    void concurrentChangesAtPublicationCannotAlterTheReceiptOrFirstResponse() {
        doAnswer(call -> {
            call.callRealMethod();
            PrometheusWorkspace published = call.getArgument(1);
            published.setAlias("updated");
            published.getTags().put("team", "two");
            return null;
        }).when(workspaces).put(anyString(), any());
        Map<String, Object> request = Map.of("alias", "original", "clientToken", "publication", "tags", Map.of("team", "one"));
        ApsController controller = new ApsController(new ApsService(factory, resolver), resolver, mapper);
        JsonNode initial = (JsonNode) controller.createWorkspace(headers, request).getEntity();
        PrometheusWorkspace receipt = receipts.get(REGION + "::publication").orElseThrow();
        assertEquals("original", receipt.getAlias());
        assertEquals("one", initial.path("tags").path("team").asText());
        assertEquals("updated", workspaces.scan(key -> true).getFirst().getAlias());
        JsonNode replay = (JsonNode) controller.createWorkspace(headers, request).getEntity();
        assertEquals(initial, replay);
    }
}
