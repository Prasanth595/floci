package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ServiceQuotasTest {

    private static final String STANDARD_CODE = "L-1216C47A";

    @Test
    void standardEc2DefaultQuotaUsesCanonicalMetadata() {
        assumeFalse(TestFixtures.isRealAws(), "Tests the emulated static quota catalog");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            ServiceQuota quota = client.getAWSDefaultServiceQuota(request -> request
                    .serviceCode("ec2").quotaCode(STANDARD_CODE)).quota();
            assertStandardMetadata(quota);
            assertThat(client.listAWSDefaultServiceQuotasPaginator(request -> request.serviceCode("ec2"))
                    .quotas().stream().filter(item -> STANDARD_CODE.equals(item.quotaCode())).toList())
                    .containsExactly(quota);
        }
    }

    @Test
    void emulatedAppliedQuotaReportsTheStaticDefault() {
        assumeFalse(TestFixtures.isRealAws(), "Real applied quotas can differ from catalog defaults");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            ServiceQuota quota = client.getServiceQuota(request -> request
                    .serviceCode("ec2").quotaCode(STANDARD_CODE)).quota();
            assertStandardMetadata(quota);
            assertThat(client.listServiceQuotasPaginator(request -> request.serviceCode("ec2"))
                    .quotas().stream().filter(item -> STANDARD_CODE.equals(item.quotaCode())).toList())
                    .containsExactly(quota);
        }
    }

    private static void assertStandardMetadata(ServiceQuota quota) {
        assertThat(quota).isNotNull();
        assertThat(quota.serviceCode()).isEqualTo("ec2");
        assertThat(quota.quotaCode()).isEqualTo(STANDARD_CODE);
        assertThat(quota.quotaName())
                .isEqualTo("Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances");
        assertThat(quota.value()).isEqualTo(5.0);
        assertThat(quota.adjustable()).isTrue();
        assertThat(quota.globalQuota()).isFalse();
    }
}
