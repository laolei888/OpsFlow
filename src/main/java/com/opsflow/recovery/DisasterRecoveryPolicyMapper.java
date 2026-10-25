package com.opsflow.recovery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DisasterRecoveryPolicyMapper extends BaseMapper<DisasterRecoveryPolicy> {
    @Select("""
            SELECT tenant_id FROM disaster_recovery_policies
            UNION SELECT tenant_id FROM backup_snapshots
            UNION SELECT tenant_id FROM recovery_drills
            """)
    List<Long> selectKnownTenantIds();
}
