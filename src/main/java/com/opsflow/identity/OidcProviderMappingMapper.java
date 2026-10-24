package com.opsflow.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OidcProviderMappingMapper extends BaseMapper<OidcProviderMapping> {
    @Select("SELECT * FROM oidc_provider_mappings WHERE registration_id = #{registrationId} LIMIT 1")
    OidcProviderMapping selectByRegistrationId(String registrationId);
}
