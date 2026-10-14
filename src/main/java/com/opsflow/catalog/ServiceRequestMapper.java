package com.opsflow.catalog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ServiceRequestMapper extends BaseMapper<ServiceRequest> {
    @Select("SELECT * FROM service_requests WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    ServiceRequest selectForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
