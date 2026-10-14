package com.opsflow.postmortem;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IncidentPostmortemActionMapper extends BaseMapper<IncidentPostmortemAction> {
    @Select("SELECT * FROM incident_postmortem_actions WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    IncidentPostmortemAction selectForUpdate(Long id, Long tenantId);
}
