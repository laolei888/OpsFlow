package com.opsflow.attachment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@ConfigurationProperties(prefix = "opsflow.storage")
public class AttachmentStorageProperties {
    @NotNull
    private AttachmentStorageType type = AttachmentStorageType.LOCAL;
    @NotBlank
    private String uploadDir = "/app/uploads";
    @Min(1)
    private long maxFileSize = 10L * 1024 * 1024;
    @NotNull
    private Duration presignedDownloadTtl = Duration.ofMinutes(5);
    @Valid
    private final S3 s3 = new S3();

    @Getter
    @Setter
    public static class S3 {
        private boolean enabled;
        private String endpoint = "";
        private String publicEndpoint = "";
        @NotBlank
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        @NotBlank
        private String bucket = "opsflow-attachments";
        private boolean pathStyleAccess = true;
        private boolean autoCreateBucket = true;
    }
}
