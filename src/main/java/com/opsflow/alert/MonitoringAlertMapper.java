package com.opsflow.alert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonitoringAlertMapper extends BaseMapper<MonitoringAlert> {
    @Select("SELECT * FROM monitoring_alerts WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    MonitoringAlert selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Select("SELECT * FROM monitoring_alerts WHERE tenant_id = #{tenantId} AND source_id = #{sourceId} " +
            "AND fingerprint = #{fingerprint} FOR UPDATE")
    MonitoringAlert selectByFingerprintForUpdate(@Param("tenantId") Long tenantId,
                                                  @Param("sourceId") Long sourceId,
                                                  @Param("fingerprint") String fingerprint);
}
