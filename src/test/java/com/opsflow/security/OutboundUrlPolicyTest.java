package com.opsflow.security;

import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboundUrlPolicyTest {
    private final OpsFlowSecurityProperties properties = new OpsFlowSecurityProperties();
    private final OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

    @Test
    void rejectsLoopbackAndCloudMetadataTargets() {
        assertThrows(BusinessException.class, () -> policy.validate("http://127.0.0.1:8080/internal"));
        assertThrows(BusinessException.class, () -> policy.validate("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void rejectsUserInfoAndNonHttpSchemes() {
        assertThrows(BusinessException.class, () -> policy.validate("https://user:password@example.com/hook"));
        assertThrows(BusinessException.class, () -> policy.validate("file:///etc/passwd"));
    }

    @Test
    void explicitHostAllowlistCanEnableAnInternalIntegration() {
        properties.getOutboundRequests().setAllowedHosts("127.0.0.1");
        assertDoesNotThrow(() -> policy.validate("http://127.0.0.1:9000/hook"));
    }

    @Test
    void productionStylePolicyRejectsPlainHttp() {
        properties.getOutboundRequests().setAllowHttp(false);
        assertThrows(BusinessException.class, () -> policy.validate("http://203.0.113.8/hook"));
    }
}
