package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketAttachmentMapper extends BaseMapper<TicketAttachment> {
    @Select("SELECT * FROM ticket_attachments WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    TicketAttachment selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
