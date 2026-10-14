package com.opsflow.supportgroup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SupportGroupMapper extends BaseMapper<SupportGroup> {
    @Select("SELECT * FROM support_groups WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    SupportGroup selectForUpdate(Long id, Long tenantId);
}
