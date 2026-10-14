package com.opsflow.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
    @Delete("DELETE FROM user_roles WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);
}
