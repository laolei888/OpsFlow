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
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        configureCors();
        configureVersioning();
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
    public StoredAttachmentObject move(String sourceKey, String targetKey) {
        if (exists(targetKey)) throw new AttachmentStorageException("S3 附件目标对象已存在");
        boolean copied = false;
        try {
            String copySource = URLEncoder.encode(properties.getBucket() + "/" + sourceKey,
                    StandardCharsets.UTF_8).replace("%2F", "/");
            var response = client.copyObject(CopyObjectRequest.builder()
                    .copySource(copySource)
                    .destinationBucket(properties.getBucket())
                    .destinationKey(targetKey)
                    .build());
            copied = true;
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket()).key(sourceKey).build());
            String etag = response.copyObjectResult() == null ? null : response.copyObjectResult().eTag();
            return new StoredAttachmentObject(etag, response.versionId());
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
    public boolean supportsInventory() {
        return true;
    }

    @Override
    public StoredObjectInventory inventory(String prefix, int limit) {
        int safeLimit = Math.max(1, limit);
        try {
            List<StoredObjectInventoryItem> items = new ArrayList<>();
            String keyMarker = null;
            String versionMarker = null;
            boolean truncated;
            do {
                var builder = ListObjectVersionsRequest.builder()
                        .bucket(properties.getBucket()).prefix(prefix).maxKeys(Math.min(1000, safeLimit + 1));
                if (keyMarker != null) builder.keyMarker(keyMarker);
                if (versionMarker != null) builder.versionIdMarker(versionMarker);
                var response = client.listObjectVersions(builder.build());
                response.versions().forEach(version -> items.add(new StoredObjectInventoryItem(
                        version.key(), version.size(), version.lastModified(), version.eTag(),
                        version.versionId(), Boolean.TRUE.equals(version.isLatest()), false)));
                response.deleteMarkers().forEach(marker -> items.add(new StoredObjectInventoryItem(
                        marker.key(), 0, marker.lastModified(), null, marker.versionId(),
                        Boolean.TRUE.equals(marker.isLatest()), true)));
                truncated = Boolean.TRUE.equals(response.isTruncated());
                keyMarker = response.nextKeyMarker();
                versionMarker = response.nextVersionIdMarker();
            } while (truncated && items.size() <= safeLimit);
            items.sort(Comparator.comparing(StoredObjectInventoryItem::key)
                    .thenComparing(StoredObjectInventoryItem::lastModified,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            boolean limited = truncated || items.size() > safeLimit;
            List<StoredObjectInventoryItem> result = items.size() > safeLimit
                    ? items.subList(0, safeLimit) : items;
            return new StoredObjectInventory(result, limited);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 405 || ex.statusCode() == 501) {
                return currentObjectInventory(prefix, safeLimit);
            }
            throw new AttachmentStorageException("S3 附件对象巡检失败", ex);
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件对象巡检失败", ex);
        }
    }

    @Override
    public void deleteVersion(String key, String versionId) {
        try {
            var builder = DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key);
            if (versionId != null && !versionId.isBlank()) builder.versionId(versionId);
            client.deleteObject(builder.build());
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件对象版本删除失败", ex);
        }
    }

    @Override
    public boolean supportsMultipartUpload() {
        return true;
    }

    @Override
    public MultipartUploadObject createMultipartUpload(String key, String contentType,
                                                        Map<String, String> metadata) {
        try {
            var response = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .metadata(metadata)
                    .build());
            return new MultipartUploadObject(response.uploadId());
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 分片上传初始化失败", ex);
        }
    }

    @Override
    public URI presignUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        try {
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();
            var signed = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                    .signatureDuration(ttl)
                    .uploadPartRequest(request)
                    .build());
            return signed.url().toURI();
        } catch (Exception ex) {
            throw new AttachmentStorageException("S3 分片上传地址生成失败", ex);
        }
    }

    @Override
    public List<MultipartUploadedPart> listMultipartParts(String key, String uploadId) {
        try {
            List<MultipartUploadedPart> parts = new ArrayList<>();
            Integer marker = null;
            boolean truncated;
            do {
                var builder = ListPartsRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .uploadId(uploadId);
                if (marker != null) builder.partNumberMarker(marker);
                var response = client.listParts(builder.build());
                response.parts().forEach(part -> parts.add(new MultipartUploadedPart(
                        part.partNumber(), part.eTag(), part.size())));
                truncated = Boolean.TRUE.equals(response.isTruncated());
                marker = response.nextPartNumberMarker();
            } while (truncated);
            return parts.stream().sorted(Comparator.comparingInt(MultipartUploadedPart::partNumber)).toList();
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 已上传分片查询失败", ex);
        }
    }

    @Override
    public StoredAttachmentObject completeMultipartUpload(String key, String uploadId,
                                                           List<CompletedMultipartPart> parts) {
        try {
            List<CompletedPart> completed = parts.stream()
                    .sorted(Comparator.comparingInt(CompletedMultipartPart::partNumber))
                    .map(part -> CompletedPart.builder()
                            .partNumber(part.partNumber())
                            .eTag(part.etag())
                            .build())
                    .toList();
            var response = client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
            return new StoredAttachmentObject(response.eTag(), response.versionId());
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 分片上传合并失败", ex);
        }
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) throw new AttachmentStorageException("S3 分片上传中止失败", ex);
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 分片上传中止失败", ex);
        }
    }

    @Override
    public StoredObjectMetadata metadata(String key) {
        try {
            var response = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket()).key(key).build());
            return new StoredObjectMetadata(response.contentLength(), response.eTag(), response.versionId());
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件元数据查询失败", ex);
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

    private void configureCors() {
        if (!properties.isAutoConfigureCors()) return;
        List<String> origins = properties.getCorsAllowedOrigins().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("启用 S3 CORS 自动配置时必须提供允许的来源");
        }
        try {
            CORSRule rule = CORSRule.builder()
                    .allowedMethods("PUT")
                    .allowedOrigins(origins)
                    .allowedHeaders("*")
                    .exposeHeaders("ETag", "x-amz-request-id")
                    .maxAgeSeconds(3600)
                    .build();
            client.putBucketCors(PutBucketCorsRequest.builder()
                    .bucket(properties.getBucket())
                    .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                    .build());
            log.info("Configured browser multipart upload CORS for attachment bucket {}", properties.getBucket());
        } catch (SdkException ex) {
            throw new IllegalStateException("无法配置 S3 附件桶跨域规则", ex);
        }
    }

    private void configureVersioning() {
        if (!properties.isAutoConfigureVersioning()) return;
        try {
            client.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(properties.getBucket())
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED).build())
                    .build());
            log.info("Enabled object versioning for attachment bucket {}", properties.getBucket());
        } catch (SdkException ex) {
            throw new IllegalStateException("无法启用 S3 附件桶版本控制", ex);
        }
    }

    private StoredObjectInventory currentObjectInventory(String prefix, int limit) {
        try {
            List<StoredObjectInventoryItem> items = new ArrayList<>();
            String continuationToken = null;
            boolean truncated;
            do {
                var builder = ListObjectsV2Request.builder().bucket(properties.getBucket())
                        .prefix(prefix).maxKeys(Math.min(1000, limit + 1));
                if (continuationToken != null) builder.continuationToken(continuationToken);
                var response = client.listObjectsV2(builder.build());
                response.contents().forEach(item -> items.add(new StoredObjectInventoryItem(
                        item.key(), item.size(), item.lastModified(), item.eTag(),
                        null, true, false)));
                truncated = Boolean.TRUE.equals(response.isTruncated());
                continuationToken = response.nextContinuationToken();
            } while (truncated && items.size() <= limit);
            boolean limited = truncated || items.size() > limit;
            List<StoredObjectInventoryItem> result = items.size() > limit
                    ? items.subList(0, limit) : items;
            return new StoredObjectInventory(result, limited);
        } catch (SdkException ex) {
            throw new AttachmentStorageException("S3 附件对象巡检失败", ex);
        }
    }
}
