package com.opsflow.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "opsflow.security")
public class OpsFlowSecurityProperties {
    private boolean registrationEnabled = true;
    private boolean publicMetrics = true;
    private String corsAllowedOrigins = "";

    @Valid
    private final LoginProtection loginProtection = new LoginProtection();
    @Valid
    private final RateLimit rateLimit = new RateLimit();
    @Valid
    private final OutboundRequests outboundRequests = new OutboundRequests();

    @Getter
    @Setter
    public static class LoginProtection {
        @Min(3)
        @Max(20)
        private int maxFailures = 5;
        private Duration observationWindow = Duration.ofMinutes(15);
        private Duration lockDuration = Duration.ofMinutes(15);
    }

    @Getter
    @Setter
    public static class RateLimit {
        @Min(1)
        private int loginAttempts = 10;
        private Duration loginWindow = Duration.ofMinutes(1);
        @Min(1)
        private int registrationAttempts = 5;
        private Duration registrationWindow = Duration.ofHours(1);
        @Min(1)
        private int writeRequests = 60;
        private Duration writeWindow = Duration.ofMinutes(1);
        @Min(1)
        private int alertIngestRequests = 300;
        private Duration alertIngestWindow = Duration.ofMinutes(1);
    }

    @Getter
    @Setter
    public static class OutboundRequests {
        private boolean allowHttp = true;
        private boolean allowPrivateNetworks = false;
        private String allowedHosts = "";
    }
}
