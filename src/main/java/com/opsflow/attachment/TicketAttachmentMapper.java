package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketAttachmentMapper extends BaseMapper<TicketAttachment> {
    @Select("SELECT * FROM ticket_attachments WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    TicketAttachment selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM ticket_attachments WHERE tenant_id = #{tenantId}")
    long sumSizeByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT COUNT(*) FROM ticket_attachments WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") Long tenantId);
}
