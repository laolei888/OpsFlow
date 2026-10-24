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
        requireStore(properties.getType());
    }

    public AttachmentStorageType configuredType() {
        return properties.getType();
    }

    public long maxFileSize() {
        return properties.getMaxFileSize();
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

    public void move(TicketAttachment attachment, String targetKey) {
        storeFor(attachment).move(validKey(attachment.getStoragePath()), validKey(targetKey));
    }

    public void move(AttachmentStorageType type, String sourceKey, String targetKey) {
        requireStore(type).move(validKey(sourceKey), validKey(targetKey));
    }

    public void delete(TicketAttachment attachment) {
        storeFor(attachment).delete(validKey(attachment.getStoragePath()));
    }

    public boolean exists(TicketAttachment attachment) {
        return storeFor(attachment).exists(validKey(attachment.getStoragePath()));
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

    private String validKey(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")
                || value.contains("../") || value.equals("..") || value.chars().anyMatch(Character::isISOControl)) {
            throw new AttachmentStorageException("附件对象键非法");
        }
        return value;
    }

    public record StorageStatus(AttachmentStorageType configuredBackend,
                                List<AttachmentStorageType> availableBackends,
                                String bucket,
                                Duration presignedDownloadTtl) { }

    public record PresignedObject(URI uri, Instant expiresAt) { }
}
