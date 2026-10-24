package com.opsflow.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OidcLoginExchangeMapper extends BaseMapper<OidcLoginExchange> {
    @Select("SELECT * FROM oidc_login_exchanges WHERE code_hash = #{codeHash} LIMIT 1 FOR UPDATE")
    OidcLoginExchange selectForUpdate(String codeHash);
}
