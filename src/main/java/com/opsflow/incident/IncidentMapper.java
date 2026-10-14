package com.opsflow.incident;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IncidentMapper extends BaseMapper<Incident> {
    @Select("SELECT * FROM incidents WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Incident selectForUpdate(Long id, Long tenantId);
}
