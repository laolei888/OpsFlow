package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AttachmentUploadSessionMapper extends BaseMapper<AttachmentUploadSession> {
    @Select("SELECT * FROM attachment_upload_sessions " +
            "WHERE session_key = #{sessionKey} AND tenant_id = #{tenantId} FOR UPDATE")
    AttachmentUploadSession selectForUpdate(@Param("sessionKey") String sessionKey,
                                            @Param("tenantId") Long tenantId);
}
