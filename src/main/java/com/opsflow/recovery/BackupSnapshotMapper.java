package com.opsflow.recovery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BackupSnapshotMapper extends BaseMapper<BackupSnapshot> {
    @Update("""
            UPDATE backup_snapshots
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE tenant_id = #{tenantId} AND status = 'AVAILABLE'
              AND completed_at < #{expiresBefore}
            """)
    int expireBefore(@Param("tenantId") Long tenantId,
                     @Param("expiresBefore") LocalDateTime expiresBefore,
                     @Param("now") LocalDateTime now);
}
