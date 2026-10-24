package com.opsflow.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserExternalIdentityMapper extends BaseMapper<UserExternalIdentity> {
    @Select("SELECT * FROM user_external_identities WHERE provider_id = #{providerId} AND subject = #{subject} LIMIT 1")
    UserExternalIdentity selectByProviderAndSubject(Long providerId, String subject);
}
