package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AttachmentStoragePolicyMapper extends BaseMapper<AttachmentStoragePolicy> {
    @Insert("""
            INSERT IGNORE INTO attachment_storage_policies
                (tenant_id, quota_bytes, warning_percent, created_at, updated_at)
            VALUES (#{tenantId}, #{quotaBytes}, #{warningPercent}, #{now}, #{now})
            """)
    int insertDefault(@Param("tenantId") Long tenantId, @Param("quotaBytes") long quotaBytes,
                      @Param("warningPercent") int warningPercent, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM attachment_storage_policies WHERE tenant_id = #{tenantId} FOR UPDATE")
    AttachmentStoragePolicy selectForUpdate(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT tenant_id FROM attachment_storage_policies
            UNION SELECT DISTINCT tenant_id FROM ticket_attachments
            UNION SELECT DISTINCT tenant_id FROM attachment_upload_sessions
            ORDER BY tenant_id
            """)
    List<Long> selectKnownTenantIds();
}
