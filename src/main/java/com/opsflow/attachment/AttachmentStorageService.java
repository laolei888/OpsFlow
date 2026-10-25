package com.opsflow.attachment;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AttachmentStorageService {
    private final AttachmentStorageProperties properties;
    private final Map<AttachmentStorageType, AttachmentObjectStore> stores;

    public AttachmentStorageService(AttachmentStorageProperties properties, List<AttachmentObjectStore> stores) {
        this.properties = properties;
        this.stores = new EnumMap<>(AttachmentStorageType.class);
        for (AttachmentObjectStore store : stores) {
            if (this.stores.put(store.type(), store) != null) {
                throw new IllegalStateException("重复的附件存储适配器: " + store.type());
            }
        }
    }

    @PostConstruct
    void validateConfiguration() {
        AttachmentObjectStore configured = requireStore(properties.getType());
        AttachmentStorageProperties.Multipart multipart = properties.getMultipart();
        if (multipart.isEnabled() && properties.getType() == AttachmentStorageType.S3
                && !configured.supportsMultipartUpload()) {
            throw new IllegalStateException("已启用附件分片上传，但当前存储适配器不支持该能力");
        }
        if (multipart.getPartSize() > multipart.getMaxFileSize()) {
            throw new IllegalStateException("附件分片大小不能超过分片上传文件上限");
        }
    }

    public AttachmentStorageType configuredType() {
        return properties.getType();
    }

    public long maxFileSize() {
        return properties.getMaxFileSize();
    }

    public MultipartCapabilities multipartCapabilities() {
        AttachmentStorageProperties.Multipart multipart = properties.getMultipart();
        AttachmentObjectStore configured = requireStore(configuredType());
        boolean enabled = multipart.isEnabled() && configured.supportsMultipartUpload();
        return new MultipartCapabilities(enabled, properties.getMaxFileSize(), multipart.getMaxFileSize(),
                multipart.getPartSize(), multipart.getSessionTtl(), multipart.getPresignedPartTtl());
    }

    public StoredAttachmentObject put(String key, byte[] content, String contentType,
                                      Map<String, String> metadata) {
        return requireStore(configuredType()).put(validKey(key), content, contentType, metadata);
    }

    public Resource load(TicketAttachment attachment) {
        return storeFor(attachment).load(validKey(attachment.getStoragePath()));
    }

    public MaterializedAttachment materialize(TicketAttachment attachment) {
        return storeFor(attachment).materialize(validKey(attachment.getStoragePath()));
    }

    public StoredAttachmentObject move(TicketAttachment attachment, String targetKey) {
        return storeFor(attachment).move(validKey(attachment.getStoragePath()), validKey(targetKey));
    }

    public StoredAttachmentObject move(AttachmentStorageType type, String sourceKey, String targetKey) {
        return requireStore(type).move(validKey(sourceKey), validKey(targetKey));
    }

    public void delete(TicketAttachment attachment) {
        storeFor(attachment).delete(validKey(attachment.getStoragePath()));
    }

    public boolean exists(TicketAttachment attachment) {
        return storeFor(attachment).exists(validKey(attachment.getStoragePath()));
    }

    public boolean exists(AttachmentStorageType type, String key) {
        return requireStore(type).exists(validKey(key));
    }

    public void delete(AttachmentStorageType type, String key) {
        requireStore(type).delete(validKey(key));
    }

    public MultipartUploadObject createMultipartUpload(String key, String contentType,
                                                        Map<String, String> metadata) {
        AttachmentObjectStore store = multipartStore(configuredType());
        return store.createMultipartUpload(validKey(key), contentType, metadata);
    }

    public PresignedUploadPart presignUploadPart(AttachmentStorageType type, String key,
                                                 String uploadId, int partNumber, Duration remainingSessionTtl) {
        Duration configuredTtl = properties.getMultipart().getPresignedPartTtl();
        Duration ttl = configuredTtl.compareTo(remainingSessionTtl) > 0 ? remainingSessionTtl : configuredTtl;
        if (ttl.isNegative() || ttl.isZero()) throw new AttachmentStorageException("分片上传会话已过期");
        Duration safeTtl = ttl.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : ttl;
        URI uri = multipartStore(type).presignUploadPart(validKey(key), uploadId, partNumber, safeTtl);
        return new PresignedUploadPart(uri, Instant.now().plus(safeTtl));
    }

    public List<MultipartUploadedPart> listMultipartParts(AttachmentStorageType type, String key,
                                                          String uploadId) {
        return multipartStore(type).listMultipartParts(validKey(key), uploadId);
    }

    public StoredAttachmentObject completeMultipartUpload(AttachmentStorageType type, String key,
                                                           String uploadId,
                                                           List<CompletedMultipartPart> parts) {
        return multipartStore(type).completeMultipartUpload(validKey(key), uploadId, parts);
    }

    public void abortMultipartUpload(AttachmentStorageType type, String key, String uploadId) {
        multipartStore(type).abortMultipartUpload(validKey(key), uploadId);
    }

    public StoredObjectMetadata metadata(AttachmentStorageType type, String key) {
        return requireStore(type).metadata(validKey(key));
    }

    public StoredObjectInventory inventory(AttachmentStorageType type, String prefix, int limit) {
        AttachmentObjectStore store = requireStore(type);
        if (!store.supportsInventory()) {
            throw new AttachmentStorageException("当前附件存储后端不支持对象巡检");
        }
        return store.inventory(validPrefix(prefix), Math.max(1, limit));
    }

    public void deleteVersion(AttachmentStorageType type, String key, String versionId) {
        requireStore(type).deleteVersion(validKey(key), versionId);
    }

    public List<AttachmentStorageType> inventoryBackends() {
        return stores.values().stream().filter(AttachmentObjectStore::supportsInventory)
                .map(AttachmentObjectStore::type).sorted().toList();
    }

    public Optional<PresignedObject> presignDownload(TicketAttachment attachment) {
        Duration ttl = properties.getPresignedDownloadTtl();
        if (ttl.isNegative() || ttl.isZero()) return Optional.empty();
        Duration safeTtl = ttl.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : ttl;
        return storeFor(attachment).presignDownload(validKey(attachment.getStoragePath()),
                        attachment.getOriginalName(), attachment.getContentType(), safeTtl)
                .map(uri -> new PresignedObject(uri, Instant.now().plus(safeTtl)));
    }

    public StorageStatus status() {
        AttachmentStorageProperties.S3 s3 = properties.getS3();
        return new StorageStatus(configuredType(), stores.keySet().stream().sorted().toList(),
                configuredType() == AttachmentStorageType.S3 ? s3.getBucket() : null,
                properties.getPresignedDownloadTtl());
    }

    private AttachmentObjectStore storeFor(TicketAttachment attachment) {
        AttachmentStorageType type = attachment.getStorageBackend() == null
                ? AttachmentStorageType.LOCAL : attachment.getStorageBackend();
        return requireStore(type);
    }

    private AttachmentObjectStore requireStore(AttachmentStorageType type) {
        AttachmentObjectStore store = stores.get(type);
        if (store == null) {
            throw new AttachmentStorageException("附件存储后端未启用: " + type);
        }
        return store;
    }

    private AttachmentObjectStore multipartStore(AttachmentStorageType type) {
        AttachmentObjectStore store = requireStore(type);
        if (!store.supportsMultipartUpload()) {
            throw new AttachmentStorageException("当前附件存储后端不支持分片上传");
        }
        return store;
    }

    private String validKey(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new AttachmentStorageException("附件对象键非法");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new AttachmentStorageException("附件对象键非法");
            }
        }
        return value;
    }

    private String validPrefix(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new AttachmentStorageException("附件对象前缀非法");
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        validKey(normalized);
        return normalized + "/";
    }

    public record StorageStatus(AttachmentStorageType configuredBackend,
                                List<AttachmentStorageType> availableBackends,
                                String bucket,
                                Duration presignedDownloadTtl) { }

    public record PresignedObject(URI uri, Instant expiresAt) { }

    public record PresignedUploadPart(URI uri, Instant expiresAt) { }

    public record MultipartCapabilities(boolean enabled, long simpleUploadMaxBytes,
                                        long multipartUploadMaxBytes, long partSize,
                                        Duration sessionTtl, Duration presignedPartTtl) { }
}
