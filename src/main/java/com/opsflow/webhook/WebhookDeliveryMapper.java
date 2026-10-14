package com.opsflow.webhook;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {
    @Select("SELECT * FROM webhook_deliveries WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    WebhookDelivery selectForUpdate(Long id, Long tenantId);
}
