package com.opsflow.attachment;

import org.springframework.core.io.Resource;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public interface AttachmentObjectStore {
    AttachmentStorageType type();

    StoredAttachmentObject put(String key, byte[] content, String contentType, Map<String, String> metadata);

    Resource load(String key);

    MaterializedAttachment materialize(String key);

    void move(String sourceKey, String targetKey);

    void delete(String key);

    boolean exists(String key);

    default Optional<URI> presignDownload(String key, String filename, String contentType, Duration ttl) {
        return Optional.empty();
    }
}
