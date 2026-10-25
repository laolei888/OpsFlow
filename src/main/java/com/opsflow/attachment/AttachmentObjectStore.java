package com.opsflow.attachment;

import org.springframework.core.io.Resource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
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

    default boolean supportsMultipartUpload() {
        return false;
    }

    default MultipartUploadObject createMultipartUpload(String key, String contentType,
                                                         Map<String, String> metadata) {
        throw new AttachmentStorageException("当前附件存储后端不支持分片上传");
    }

    default URI presignUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        throw new AttachmentStorageException("当前附件存储后端不支持分片上传");
    }

    default List<MultipartUploadedPart> listMultipartParts(String key, String uploadId) {
        throw new AttachmentStorageException("当前附件存储后端不支持分片上传");
    }

    default StoredAttachmentObject completeMultipartUpload(String key, String uploadId,
                                                            List<CompletedMultipartPart> parts) {
        throw new AttachmentStorageException("当前附件存储后端不支持分片上传");
    }

    default void abortMultipartUpload(String key, String uploadId) {
        throw new AttachmentStorageException("当前附件存储后端不支持分片上传");
    }

    default StoredObjectMetadata metadata(String key) {
        throw new AttachmentStorageException("当前附件存储后端不支持对象元数据查询");
    }

    default Optional<URI> presignDownload(String key, String filename, String contentType, Duration ttl) {
        return Optional.empty();
    }
}
