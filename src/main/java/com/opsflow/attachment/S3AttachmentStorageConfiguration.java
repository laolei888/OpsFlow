package com.opsflow.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@ConditionalOnProperty(prefix = "opsflow.storage.s3", name = "enabled", havingValue = "true")
public class S3AttachmentStorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client attachmentS3Client(AttachmentStorageProperties properties) {
        AttachmentStorageProperties.S3 settings = properties.getS3();
        var builder = S3Client.builder()
                .region(Region.of(settings.getRegion()))
                .credentialsProvider(credentials(settings))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(settings.isPathStyleAccess())
                        .build());
        if (StringUtils.hasText(settings.getEndpoint())) {
            builder.endpointOverride(URI.create(settings.getEndpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner attachmentS3Presigner(AttachmentStorageProperties properties) {
        AttachmentStorageProperties.S3 settings = properties.getS3();
        var builder = S3Presigner.builder()
                .region(Region.of(settings.getRegion()))
                .credentialsProvider(credentials(settings))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(settings.isPathStyleAccess())
                        .build());
        String endpoint = StringUtils.hasText(settings.getPublicEndpoint())
                ? settings.getPublicEndpoint() : settings.getEndpoint();
        if (StringUtils.hasText(endpoint)) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    private AwsCredentialsProvider credentials(AttachmentStorageProperties.S3 settings) {
        boolean hasAccessKey = StringUtils.hasText(settings.getAccessKey());
        boolean hasSecretKey = StringUtils.hasText(settings.getSecretKey());
        if (hasAccessKey != hasSecretKey) {
            throw new IllegalStateException("S3 access key 与 secret key 必须同时配置");
        }
        if (!hasAccessKey) return DefaultCredentialsProvider.create();
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                settings.getAccessKey(), settings.getSecretKey()));
    }
}
