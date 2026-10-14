package com.opsflow.runbook;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunbookMapper extends BaseMapper<Runbook> {
    @Select("SELECT * FROM runbooks WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Runbook selectForUpdate(Long id, Long tenantId);
}
