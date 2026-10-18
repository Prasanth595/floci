package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.restassured.path.xml.XmlPath;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoScalingSuspensionReasonTest {
    @Test
    void legacyProcessNamesDescribeCurrentStateWithoutInventingStoredHistory() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AutoScalingGroup group = mapper.readValue("""
                {"autoScalingGroupName":"legacy-reason", "region":"us-east-1",
                 "autoScalingGroupArn":"arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:id:autoScalingGroupName/legacy-reason",
                 "createdTime":"2026-10-18T00:00:00Z", "suspendedProcesses":["Launch","Terminate"]}
                """, AutoScalingGroup.class);
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver("us-east-1", "000000000000");
        service.groups.put(AutoScalingService.asgKey("us-east-1", "legacy-reason"), group);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AutoScalingGroupNames.member.1", "legacy-reason");
        Response response = new AutoScalingQueryHandler(service).handle("DescribeAutoScalingGroups", params, "us-east-1");
        assertEquals(200, response.getStatus());
        XmlPath xml = new XmlPath((String) response.getEntity());
        String path = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member"
                + ".SuspendedProcesses.member";
        assertEquals(List.of("Launch", "Terminate"), xml.getList(path + ".ProcessName", String.class));
        assertEquals(List.of("Suspended by local configuration.", "Suspended by local configuration."),
                xml.getList(path + ".SuspensionReason", String.class));
        assertFalse(mapper.writeValueAsString(group).contains("SuspensionReason"));
        assertFalse(mapper.writeValueAsString(group).contains("Suspended by local configuration."));
    }
}
