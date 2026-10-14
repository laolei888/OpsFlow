package com.opsflow.webhook;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WebhookOutboxMapper extends BaseMapper<WebhookOutboxEvent> {
    @Select("SELECT * FROM webhook_outbox_events WHERE id = #{id} FOR UPDATE")
    WebhookOutboxEvent selectForUpdate(Long id);

    @Select("SELECT * FROM webhook_outbox_events WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    WebhookOutboxEvent selectForUpdateInTenant(Long id, Long tenantId);
}
