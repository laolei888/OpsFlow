package com.opsflow.change;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChangeRequestMapper extends BaseMapper<ChangeRequest> {
    @Select("SELECT * FROM change_requests WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    ChangeRequest selectForUpdate(Long id, Long tenantId);
}
