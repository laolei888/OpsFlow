package com.opsflow.supportgroup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SupportGroupMemberMapper extends BaseMapper<SupportGroupMember> {
    @Select("SELECT * FROM support_group_members "
            + "WHERE group_id = #{groupId} AND tenant_id = #{tenantId} AND active = TRUE "
            + "ORDER BY joined_at ASC, id ASC FOR UPDATE")
    List<SupportGroupMember> selectActiveForUpdate(Long groupId, Long tenantId);
}
