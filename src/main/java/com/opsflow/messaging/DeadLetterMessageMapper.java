package com.opsflow.messaging;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeadLetterMessageMapper extends BaseMapper<DeadLetterMessage> {
    @Select("SELECT * FROM async_dead_letter_messages WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    DeadLetterMessage selectForUpdateInTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Select("SELECT * FROM async_dead_letter_messages WHERE tenant_id = #{tenantId} AND message_id = #{messageId} FOR UPDATE")
    DeadLetterMessage selectForUpdateByMessageId(@Param("tenantId") Long tenantId,
                                                  @Param("messageId") String messageId);
}
