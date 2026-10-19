package com.opsflow.notification;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationDeliveryMapper extends BaseMapper<NotificationDelivery> {
    @Select("SELECT * FROM notification_deliveries WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    NotificationDelivery selectForUpdate(Long id, Long tenantId);
}
