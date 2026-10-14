package com.opsflow.runbook;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunbookExecutionMapper extends BaseMapper<RunbookExecution> {
    @Select("SELECT * FROM runbook_executions WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    RunbookExecution selectForUpdate(Long id, Long tenantId);
}
