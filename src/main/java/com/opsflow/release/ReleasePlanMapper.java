package com.opsflow.release;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReleasePlanMapper extends BaseMapper<ReleasePlan> {
    @Select("SELECT * FROM release_plans WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    ReleasePlan selectForUpdate(Long id, Long tenantId);
}
