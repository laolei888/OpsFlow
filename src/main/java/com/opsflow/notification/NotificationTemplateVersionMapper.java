package com.opsflow.notification;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationTemplateVersionMapper extends BaseMapper<NotificationTemplateVersion> {
    @Select("SELECT * FROM notification_template_versions WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    NotificationTemplateVersion selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
