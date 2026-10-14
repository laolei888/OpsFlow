package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
    @Select("SELECT * FROM tickets WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Ticket selectForUpdate(Long id, Long tenantId);
}
