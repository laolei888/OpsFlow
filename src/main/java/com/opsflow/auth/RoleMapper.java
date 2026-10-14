package com.opsflow.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {
    @Select("SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<String> selectCodesByUserId(Long userId);

    @Select("SELECT id FROM roles WHERE code = #{code} LIMIT 1")
    Long selectIdByCode(String code);
}
