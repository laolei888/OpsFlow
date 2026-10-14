package com.opsflow.postmortem;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IncidentPostmortemMapper extends BaseMapper<IncidentPostmortem> {
    @Select("SELECT * FROM incident_postmortems WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    IncidentPostmortem selectForUpdate(Long id, Long tenantId);
}
