package com.opsflow.notification;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationTemplateMapper extends BaseMapper<NotificationTemplate> {
    @Select("SELECT * FROM notification_templates WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    NotificationTemplate selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
