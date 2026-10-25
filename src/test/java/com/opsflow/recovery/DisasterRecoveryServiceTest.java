package com.opsflow.recovery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisasterRecoveryServiceTest {
    @Mock private DisasterRecoveryPolicyMapper policyMapper;
    @Mock private BackupSnapshotMapper snapshotMapper;
    @Mock private RecoveryDrillMapper drillMapper;
    @Mock private RecoveryDrillCheckMapper checkMapper;
    @Mock private AuditLogService auditLogService;

    @Test
    void returnsSafeEnterpriseDefaultsBeforeTenantOverride() {
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        DisasterRecoveryService service = service();

        DisasterRecoveryService.EffectivePolicy policy = service.effectivePolicy(9L);

        assertThat(policy.rpoMinutes()).isEqualTo(60);
        assertThat(policy.rtoMinutes()).isEqualTo(120);
        assertThat(policy.requireEncryption()).isTrue();
        assertThat(policy.requireImmutableCopy()).isTrue();
        assertThat(policy.inheritedDefaults()).isTrue();
    }

    @Test
    void rejectsRecoveryObjectivesOutsideGuardrails() {
        DisasterRecoveryService service = service();
        DisasterRecoveryService.PolicyCommand command = new DisasterRecoveryService.PolicyCommand(
                1, 120, 30, 24, true, true, false, true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.savePolicy(command, 9L, 7L));

        assertThat(exception.getMessage()).contains("RPO");
        verify(policyMapper, never()).insert(any(DisasterRecoveryPolicy.class));
    }

    @Test
    void registersBackupButFailsVerificationWhenImmutableWindowIsTooShort() {
        DisasterRecoveryPolicy policy = policy(9L);
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(policy);
        doAnswer(invocation -> {
            BackupSnapshot value = invocation.getArgument(0);
            value.setId(41L);
            return 1;
        }).when(snapshotMapper).insert(any(BackupSnapshot.class));
        DisasterRecoveryService service = service();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DisasterRecoveryService.BackupCommand command = new DisasterRecoveryService.BackupCommand(
                BackupScope.FULL, "S3", "s3://backups/41.dump", "cn-test-1",
                now.minusMinutes(10), now.minusMinutes(8), now.minusMinutes(5), 1024L,
                "a".repeat(64), true, now.plusDays(2), false, "backup-job-41 completed");

        BackupSnapshot result = service.registerBackup(command, 9L, 7L);

        assertThat(result.getVerificationStatus()).isEqualTo(BackupVerificationStatus.FAILED);
        assertThat(result.getVerificationDetail()).contains("不可变保留期");
        verify(snapshotMapper).updateById(result);
    }

    @Test
    void rejectsBackupLocationThatCouldLeakSignedCredentials() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DisasterRecoveryService.BackupCommand command = new DisasterRecoveryService.BackupCommand(
                BackupScope.DATABASE, "S3", "https://storage.example/backup?token=secret", "cn-test-1",
                now.minusMinutes(10), now.minusMinutes(8), now.minusMinutes(5), 1024L,
                "a".repeat(64), true, now.plusDays(30), true, "backup job evidence");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().registerBackup(command, 9L, 7L));

        assertThat(exception.getMessage()).contains("不能包含访问凭据");
        verify(snapshotMapper, never()).insert(any(BackupSnapshot.class));
    }

    @Test
    void detectsConcurrentDrillTransitionWithOptimisticLock() {
        RecoveryDrill drill = runningDrill();
        drill.setStatus(RecoveryDrillStatus.PLANNED);
        when(drillMapper.selectOne(any(Wrapper.class))).thenReturn(drill);
        when(drillMapper.updateById(any(RecoveryDrill.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().startDrill(15L, 9L, 7L));

        assertThat(exception.getMessage()).contains("其他管理员更新");
    }

    @Test
    void refusesToCreateRestoreDrillInProductionEnvironment() {
        DisasterRecoveryService.DrillCommand command = new DisasterRecoveryService.DrillCommand(
                "unsafe drill", 21L, "production-primary", null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().createDrill(command, 9L, 7L));

        assertThat(exception.getMessage()).contains("必须使用隔离环境");
        verify(drillMapper, never()).insert(any(RecoveryDrill.class));
    }

    @Test
    void refusesToUseLogicallyExpiredBackupBeforeRetentionJobRuns() {
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.setId(21L);
        snapshot.setTenantId(9L);
        snapshot.setStatus(BackupStatus.AVAILABLE);
        snapshot.setCompletedAt(LocalDateTime.now().minusDays(31));
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(snapshot);
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(policy(9L));
        DisasterRecoveryService.DrillCommand command = new DisasterRecoveryService.DrillCommand(
                "expired backup drill", 21L, "isolated-restore", null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().createDrill(command, 9L, 7L));

        assertThat(exception.getMessage()).contains("超出租户保留期");
        verify(drillMapper, never()).insert(any(RecoveryDrill.class));
    }

    @Test
    void refusesToCompleteDrillWhileRequiredChecksArePending() {
        RecoveryDrill drill = runningDrill();
        when(drillMapper.selectOne(any(Wrapper.class))).thenReturn(drill);
        RecoveryDrillCheck pending = check(1L, RecoveryCheckStatus.PENDING, true);
        when(checkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending));
        DisasterRecoveryService service = service();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.completeDrill(15L,
                        new DisasterRecoveryService.CompleteCommand("isolated restore evidence", null),
                        9L, 7L));

        assertThat(exception.getMessage()).contains("全部必需检查项");
        verify(drillMapper, never()).updateById(any(RecoveryDrill.class));
    }

    @Test
    void computesRpoRtoAndPassesCompletedDrill() {
        RecoveryDrill drill = runningDrill();
        drill.setTargetRpoMinutes(60);
        drill.setTargetRtoMinutes(60);
        drill.setStartedAt(LocalDateTime.now().plusSeconds(1));
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.setId(21L);
        snapshot.setTenantId(9L);
        snapshot.setRecoveryPointAt(LocalDateTime.now().minusMinutes(20));
        snapshot.setBackupNo("BKP-TEST");
        when(drillMapper.selectOne(any(Wrapper.class))).thenReturn(drill);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(snapshot);
        when(checkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                check(1L, RecoveryCheckStatus.PASSED, true),
                check(2L, RecoveryCheckStatus.PASSED, true)));
        when(drillMapper.updateById(any(RecoveryDrill.class))).thenReturn(1);
        DisasterRecoveryService service = service();

        DisasterRecoveryService.DrillDetail result = service.completeDrill(15L,
                new DisasterRecoveryService.CompleteCommand("database and API smoke tests passed", "no finding"),
                9L, 7L);

        assertThat(result.drill().getStatus()).isEqualTo(RecoveryDrillStatus.PASSED);
        assertThat(result.drill().getIntegrityScore()).isEqualTo(100);
        assertThat(result.drill().getAchievedRpoMinutes()).isBetween(1, 60);
        assertThat(result.drill().getAchievedRtoMinutes()).isEqualTo(1);
        verify(drillMapper).updateById(drill);
    }

    @Test
    void expiresOnlyEntriesOutsideTenantRetentionWindow() {
        DisasterRecoveryPolicy policy = policy(9L);
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(policy);
        when(snapshotMapper.expireBefore(eq(9L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(3);

        int expired = service().expireSnapshots(9L);

        assertThat(expired).isEqualTo(3);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(snapshotMapper).expireBefore(eq(9L), cutoff.capture(), any(LocalDateTime.class));
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(29));
    }

    private DisasterRecoveryService service() {
        return new DisasterRecoveryService(policyMapper, snapshotMapper, drillMapper, checkMapper, auditLogService);
    }

    private DisasterRecoveryPolicy policy(Long tenantId) {
        DisasterRecoveryPolicy policy = new DisasterRecoveryPolicy();
        policy.setId(1L);
        policy.setTenantId(tenantId);
        policy.setRpoMinutes(60);
        policy.setRtoMinutes(120);
        policy.setRetentionDays(30);
        policy.setVerificationIntervalHours(24);
        policy.setRequireEncryption(true);
        policy.setRequireImmutableCopy(true);
        policy.setRequireCrossRegionCopy(false);
        policy.setEnabled(true);
        return policy;
    }

    private RecoveryDrill runningDrill() {
        RecoveryDrill drill = new RecoveryDrill();
        drill.setId(15L);
        drill.setTenantId(9L);
        drill.setDrillNo("DR-TEST");
        drill.setBackupSnapshotId(21L);
        drill.setStatus(RecoveryDrillStatus.RUNNING);
        drill.setVersion(0);
        drill.setStartedAt(LocalDateTime.now().minusMinutes(5));
        drill.setTargetRpoMinutes(60);
        drill.setTargetRtoMinutes(120);
        return drill;
    }

    private RecoveryDrillCheck check(Long id, RecoveryCheckStatus status, boolean required) {
        RecoveryDrillCheck check = new RecoveryDrillCheck();
        check.setId(id);
        check.setDrillId(15L);
        check.setTenantId(9L);
        check.setStatus(status);
        check.setRequiredCheck(required);
        return check;
    }
}
