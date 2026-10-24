package com.opsflow.attachment;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "opsflow.storage.s3", name = "enabled", havingValue = "true")
public class S3AttachmentObjectStore implements AttachmentObjectStore {
    private static final Logger log = LoggerFactory.getLogger(S3AttachmentObjectStore.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final AttachmentStorageProperties.S3 properties;

    public S3AttachmentObjectStore(S3Client attachmentS3Client,
                                   S3Presigner attachmentS3Presigner,
                                   AttachmentStorageProperties properties) {
        this.client = attachmentS3Client;
        this.presigner = attachmentS3Presigner;
        this.properties = properties.getS3();
    }

    @PostConstruct
    void initializeBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (S3Exception ex) {
            if (!properties.isAutoCreateBucket() || ex.statusCode() != 404) {
                throw new IllegalStateException("S3 附件桶不可用: " + properties.getBucket(), ex);
            }
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
                log.info("Created attachment object storage bucket {}", properties.getBucket());
            } catch (S3Exception createFailure) {
                if (createFailure.statusCode() != 409) {
                    throw new IllegalStateException("无法创建 S3 附件桶: " + properties.getBucket(), createFailure);
                }
            } catch (SdkException createFailure) {
                throw new IllegalStateException("无法创建 S3 附件桶: " + properties.getBucket(), createFailure);
            }
        } catch (SdkException ex) {
            throw new IllegalStateException("S3 附件桶不可用: " + properties.getBucket(), ex);
        }
    }

    @Override
    public AttachmentStorageType type() {
        return AttachmentStorageType.S3;
    }

    @Override
    public StoredAttachmentObject put(String key, byte[] content, String contentType, Map<String, String> metadata) {
        try {
            var response = client.putObject(PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) content.length)
                            .metadata(metadata)
                            .build(),
                    RequestBody.fromBytes(content));
            return new StoredAttachmentObject(response.eTag(), response.versionId());
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件写入失败", ex);
        }
    }

    @Override
    public Resource load(String key) {
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket()).key(key).build());
            return new InputStreamResource(stream);
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件读取失败", ex);
        }
    }

    @Override
    public MaterializedAttachment materialize(String key) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("opsflow-attachment-");
            Path target = directory.resolve("object");
            client.getObject(GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build(),
                    ResponseTransformer.toFile(target));
            Path cleanupDirectory = directory;
            return new MaterializedAttachment(target, () -> deleteTemporary(target, cleanupDirectory));
        } catch (IOException | SdkException ex) {
            if (directory != null) deleteTemporary(directory.resolve("object"), directory);
            throw new AttachmentStorageException("S3 附件暂存失败", ex);
        }
    }

    @Override
    public void move(String sourceKey, String targetKey) {
        if (exists(targetKey)) throw new AttachmentStorageException("S3 附件目标对象已存在");
        boolean copied = false;
        try {
            String copySource = URLEncoder.encode(properties.getBucket() + "/" + sourceKey,
                    StandardCharsets.UTF_8).replace("%2F", "/");
            client.copyObject(CopyObjectRequest.builder()
                    .copySource(copySource)
                    .destinationBucket(properties.getBucket())
                    .destinationKey(targetKey)
                    .build());
            copied = true;
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket()).key(sourceKey).build());
        } catch (SdkException ex) {
            if (copied) rollbackCopiedTarget(targetKey, ex);
            throw new AttachmentStorageException("S3 附件移动失败", ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket()).key(key).build());
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件删除失败", ex);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            return true;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) return false;
            throw new AttachmentStorageException("S3 附件状态检查失败", ex);
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件状态检查失败", ex);
        }
    }

    @Override
    public Optional<URI> presignDownload(String key, String filename, String contentType, Duration ttl) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .responseContentType(contentType)
                    .responseContentDisposition(contentDisposition(filename))
                    .build();
            var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(request)
                    .build());
            return Optional.of(signed.url().toURI());
        } catch (Exception ex) {
            throw new AttachmentStorageException("S3 临时下载地址生成失败", ex);
        }
    }

    private String contentDisposition(String filename) {
        String safeAscii = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + safeAscii + "\"; filename*=UTF-8''" + encoded;
    }

    private void deleteTemporary(Path file, Path directory) {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        } catch (IOException ex) {
            log.warn("Failed to remove temporary attachment scan file {}", file, ex);
        }
    }

    private void rollbackCopiedTarget(String targetKey, SdkException originalFailure) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket()).key(targetKey).build());
        } catch (SdkException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            log.error("Failed to roll back copied S3 attachment object {}", targetKey, rollbackFailure);
        }
    }
}
