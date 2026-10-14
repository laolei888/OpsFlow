package com.opsflow.oncall;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OnCallEscalationEventMapper extends BaseMapper<OnCallEscalationEvent> {
    @Select("SELECT * FROM oncall_escalation_events WHERE id = #{id} FOR UPDATE")
    OnCallEscalationEvent selectForUpdate(Long id);
}
