package com.opsflow.messaging;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AsyncMessageOutboxMapper extends BaseMapper<AsyncMessageOutboxEvent> {
    @Select("SELECT * FROM async_message_outbox WHERE id = #{id} FOR UPDATE")
    AsyncMessageOutboxEvent selectForUpdate(Long id);

    @Select("SELECT * FROM async_message_outbox WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    AsyncMessageOutboxEvent selectForUpdateInTenant(Long id, Long tenantId);
}
