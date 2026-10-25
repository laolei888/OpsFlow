package com.opsflow.recovery;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "opsflow.recovery.backup-agent")
public class BackupAgentProperties {
    private String encryptionKey;
    private Duration signatureTolerance = Duration.ofMinutes(5);
}
