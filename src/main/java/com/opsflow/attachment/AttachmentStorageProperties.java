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
import java.util.ArrayList;
import java.util.List;

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
    @Valid
    private final Multipart multipart = new Multipart();

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
        private boolean autoConfigureCors;
        private List<String> corsAllowedOrigins = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Multipart {
        private boolean enabled = true;
        @Min(5L * 1024 * 1024)
        private long partSize = 8L * 1024 * 1024;
        @Min(1)
        private long maxFileSize = 1024L * 1024 * 1024;
        @NotNull
        private Duration presignedPartTtl = Duration.ofMinutes(15);
        @NotNull
        private Duration sessionTtl = Duration.ofHours(24);
        @Min(1)
        private int completedRetentionDays = 7;
    }
}
