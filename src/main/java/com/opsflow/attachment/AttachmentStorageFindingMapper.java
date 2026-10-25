package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AttachmentStorageFindingMapper extends BaseMapper<AttachmentStorageFinding> {
    @Insert("""
            INSERT INTO attachment_storage_findings
                (finding_fingerprint, tenant_id, storage_backend, finding_type, status,
                 object_key, version_id, size_bytes, object_last_modified_at, latest_version,
                 eligible_delete_at, detected_at, last_seen_at, created_at, updated_at)
            VALUES
                (#{item.findingFingerprint}, #{item.tenantId}, #{item.storageBackend}, #{item.findingType},
                 'OPEN', #{item.objectKey}, #{item.versionId}, #{item.sizeBytes},
                 #{item.objectLastModifiedAt}, #{item.latestVersion}, #{item.eligibleDeleteAt},
                 #{item.detectedAt}, #{item.lastSeenAt}, #{item.createdAt}, #{item.updatedAt})
            ON DUPLICATE KEY UPDATE
                size_bytes = VALUES(size_bytes),
                eligible_delete_at = IF(object_last_modified_at <=> VALUES(object_last_modified_at),
                                        eligible_delete_at, VALUES(eligible_delete_at)),
                object_last_modified_at = VALUES(object_last_modified_at),
                latest_version = VALUES(latest_version),
                last_seen_at = VALUES(last_seen_at),
                status = IF(status = 'IGNORED', status, 'OPEN'),
                failure_reason = NULL,
                updated_at = VALUES(updated_at)
            """)
    int upsertDetected(@Param("item") AttachmentStorageFinding item);

    @Select("SELECT * FROM attachment_storage_findings WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    AttachmentStorageFinding selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Update("""
            UPDATE attachment_storage_findings
            SET status = 'RESOLVED', resolved_at = #{resolvedAt}, updated_at = #{resolvedAt},
                failure_reason = NULL
            WHERE tenant_id = #{tenantId} AND storage_backend = #{backend}
              AND status IN ('OPEN', 'FAILED', 'DELETING') AND last_seen_at < #{scanStartedAt}
            """)
    int resolveNotSeen(@Param("tenantId") Long tenantId,
                       @Param("backend") AttachmentStorageType backend,
                       @Param("scanStartedAt") LocalDateTime scanStartedAt,
                       @Param("resolvedAt") LocalDateTime resolvedAt);
}
