package com.opsflow.problem;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemMapper extends BaseMapper<Problem> {
    @Select("SELECT * FROM problems WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Problem selectForUpdate(Long id, Long tenantId);
}
