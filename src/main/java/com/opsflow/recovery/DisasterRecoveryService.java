package com.opsflow.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DisasterRecoveryService {
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final int DEFAULT_RPO_MINUTES = 60;
    private static final int DEFAULT_RTO_MINUTES = 120;
    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final int DEFAULT_VERIFICATION_HOURS = 24;

    private final DisasterRecoveryPolicyMapper policyMapper;
    private final BackupSnapshotMapper snapshotMapper;
    private final RecoveryDrillMapper drillMapper;
    private final RecoveryDrillCheckMapper checkMapper;
    private final AuditLogService auditLogService;

    public RecoveryCenterView center(Long tenantId) {
        EffectivePolicy policy = effectivePolicy(tenantId);
        List<BackupSnapshot> snapshots = snapshotMapper.selectList(
                new LambdaQueryWrapper<BackupSnapshot>()
                        .eq(BackupSnapshot::getTenantId, tenantId)
                        .orderByDesc(BackupSnapshot::getRecoveryPointAt)
                        .orderByDesc(BackupSnapshot::getId)
                        .last("LIMIT 100"));
        List<RecoveryDrill> drills = drillMapper.selectList(
                new LambdaQueryWrapper<RecoveryDrill>()
                        .eq(RecoveryDrill::getTenantId, tenantId)
                        .orderByDesc(RecoveryDrill::getCreatedAt)
                        .orderByDesc(RecoveryDrill::getId)
                        .last("LIMIT 100"));
        return new RecoveryCenterView(policy, readiness(policy, snapshots, drills), snapshots, drills);
    }

    public DrillDetail drillDetail(Long id, Long tenantId) {
        RecoveryDrill drill = requireDrill(id, tenantId);
        BackupSnapshot snapshot = requireSnapshot(drill.getBackupSnapshotId(), tenantId);
        List<RecoveryDrillCheck> checks = checkMapper.selectList(
                new LambdaQueryWrapper<RecoveryDrillCheck>()
                        .eq(RecoveryDrillCheck::getTenantId, tenantId)
                        .eq(RecoveryDrillCheck::getDrillId, id)
                        .orderByAsc(RecoveryDrillCheck::getSortOrder)
                        .orderByAsc(RecoveryDrillCheck::getId));
        return new DrillDetail(drill, snapshot, checks);
    }

    public BackupSnapshot findSnapshot(Long id, Long tenantId) {
        return requireSnapshot(id, tenantId);
    }

    public EffectivePolicy effectivePolicy(Long tenantId) {
        DisasterRecoveryPolicy policy = policyMapper.selectOne(
                new LambdaQueryWrapper<DisasterRecoveryPolicy>()
                        .eq(DisasterRecoveryPolicy::getTenantId, tenantId)
                        .last("LIMIT 1"));
        if (policy == null) {
            return new EffectivePolicy(null, tenantId, DEFAULT_RPO_MINUTES, DEFAULT_RTO_MINUTES,
                    DEFAULT_RETENTION_DAYS, DEFAULT_VERIFICATION_HOURS,
                    true, true, false, true, true, null);
        }
        return toEffective(policy, false);
    }

    @Transactional
    public EffectivePolicy savePolicy(PolicyCommand command, Long tenantId, Long actorId) {
        validatePolicy(command);
        DisasterRecoveryPolicy policy = policyMapper.selectOne(
                new LambdaQueryWrapper<DisasterRecoveryPolicy>()
                        .eq(DisasterRecoveryPolicy::getTenantId, tenantId)
                        .last("LIMIT 1"));
        EffectivePolicy before = policy == null ? effectivePolicy(tenantId) : toEffective(policy, false);
        LocalDateTime now = LocalDateTime.now();
        if (policy == null) {
            policy = new DisasterRecoveryPolicy();
            policy.setTenantId(tenantId);
            policy.setCreatedBy(actorId);
            policy.setCreatedAt(now);
        }
        policy.setRpoMinutes(command.rpoMinutes());
        policy.setRtoMinutes(command.rtoMinutes());
        policy.setRetentionDays(command.retentionDays());
        policy.setVerificationIntervalHours(command.verificationIntervalHours());
        policy.setRequireEncryption(command.requireEncryption());
        policy.setRequireImmutableCopy(command.requireImmutableCopy());
        policy.setRequireCrossRegionCopy(command.requireCrossRegionCopy());
        policy.setEnabled(command.enabled());
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(now);
        if (policy.getId() == null) policyMapper.insert(policy);
        else policyMapper.updateById(policy);
        EffectivePolicy result = toEffective(policy, false);
        auditLogService.record(tenantId, actorId, "DISASTER_RECOVERY_POLICY", policy.getId(),
                "DISASTER_RECOVERY_POLICY_UPDATED", before, result, "更新租户备份与恢复目标");
        return result;
    }

    @Transactional
    public BackupSnapshot registerBackup(BackupCommand command, Long tenantId, Long actorId) {
        validateBackup(command);
        LocalDateTime now = LocalDateTime.now();
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.setTenantId(tenantId);
        snapshot.setBackupNo("BKP-" + token());
        snapshot.setScope(command.scope());
        snapshot.setStatus(BackupStatus.AVAILABLE);
        snapshot.setStorageProvider(normalize(command.storageProvider()));
        snapshot.setStorageLocation(normalize(command.storageLocation()));
        snapshot.setRegion(normalize(command.region()));
        snapshot.setRecoveryPointAt(command.recoveryPointAt());
        snapshot.setCapturedAt(command.capturedAt());
        snapshot.setCompletedAt(command.completedAt());
        snapshot.setSizeBytes(command.sizeBytes());
        snapshot.setChecksumSha256(command.checksumSha256().trim().toLowerCase(Locale.ROOT));
        snapshot.setEncrypted(command.encrypted());
        snapshot.setImmutableUntil(command.immutableUntil());
        snapshot.setCrossRegionCopy(command.crossRegionCopy());
        snapshot.setVerificationStatus(BackupVerificationStatus.PENDING);
        snapshot.setEvidenceNote(normalizeNullable(command.evidenceNote()));
        snapshot.setCreatedBy(actorId);
        snapshot.setCreatedAt(now);
        snapshot.setUpdatedAt(now);
        snapshotMapper.insert(snapshot);
        applyVerification(snapshot, effectivePolicy(tenantId), now);
        snapshotMapper.updateById(snapshot);
        auditLogService.record(tenantId, actorId, "BACKUP_SNAPSHOT", snapshot.getId(),
                "BACKUP_SNAPSHOT_REGISTERED", null, snapshot, "登记外部备份并执行策略校验");
        return snapshot;
    }

    @Transactional
    public BackupSnapshot verifyBackup(Long id, Long tenantId, Long actorId) {
        BackupSnapshot snapshot = requireSnapshot(id, tenantId);
        if (snapshot.getStatus() != BackupStatus.AVAILABLE) {
            throw new BusinessException("只有可用备份可以重新校验");
        }
        BackupVerificationStatus before = snapshot.getVerificationStatus();
        applyVerification(snapshot, effectivePolicy(tenantId), LocalDateTime.now());
        snapshotMapper.updateById(snapshot);
        auditLogService.record(tenantId, actorId, "BACKUP_SNAPSHOT", id,
                "BACKUP_SNAPSHOT_VERIFIED", Map.of("status", before),
                Map.of("status", snapshot.getVerificationStatus(), "detail", snapshot.getVerificationDetail()),
                "重新校验备份完整性与灾备策略");
        return snapshot;
    }

    @Transactional
    public DrillDetail createDrill(DrillCommand command, Long tenantId, Long actorId) {
        validateDrill(command);
        BackupSnapshot snapshot = requireSnapshot(command.backupSnapshotId(), tenantId);
        if (snapshot.getStatus() != BackupStatus.AVAILABLE) {
            throw new BusinessException("只能使用可用备份创建恢复演练");
        }
        EffectivePolicy policy = effectivePolicy(tenantId);
        if (snapshot.getCompletedAt() == null
                || snapshot.getCompletedAt().isBefore(LocalDateTime.now().minusDays(policy.retentionDays()))) {
            throw new BusinessException("该备份已超出租户保留期，不能用于新的恢复演练");
        }
        LocalDateTime now = LocalDateTime.now();
        RecoveryDrill drill = new RecoveryDrill();
        drill.setTenantId(tenantId);
        drill.setDrillNo("DR-" + token());
        drill.setName(normalize(command.name()));
        drill.setBackupSnapshotId(snapshot.getId());
        drill.setStatus(RecoveryDrillStatus.PLANNED);
        drill.setVersion(0);
        drill.setEnvironment(normalize(command.environment()));
        drill.setScheduledAt(command.scheduledAt());
        drill.setTargetRpoMinutes(policy.rpoMinutes());
        drill.setTargetRtoMinutes(policy.rtoMinutes());
        drill.setCreatedBy(actorId);
        drill.setCreatedAt(now);
        drill.setUpdatedAt(now);
        drillMapper.insert(drill);
        seedChecks(drill, snapshot, policy, now);
        auditLogService.record(tenantId, actorId, "RECOVERY_DRILL", drill.getId(),
                "RECOVERY_DRILL_CREATED", null, drill, "创建隔离环境恢复演练");
        return drillDetail(drill.getId(), tenantId);
    }

    @Transactional
    public DrillDetail startDrill(Long id, Long tenantId, Long actorId) {
        RecoveryDrill drill = requireDrill(id, tenantId);
        if (drill.getStatus() != RecoveryDrillStatus.PLANNED) {
            throw new BusinessException("只有待执行演练可以开始");
        }
        LocalDateTime now = LocalDateTime.now();
        drill.setStatus(RecoveryDrillStatus.RUNNING);
        drill.setStartedAt(now);
        drill.setStartedBy(actorId);
        drill.setUpdatedAt(now);
        updateDrill(drill);
        auditLogService.record(tenantId, actorId, "RECOVERY_DRILL", id,
                "RECOVERY_DRILL_STARTED", RecoveryDrillStatus.PLANNED, drill,
                "在隔离环境开始恢复演练");
        return drillDetail(id, tenantId);
    }

    @Transactional
    public RecoveryDrillCheck updateCheck(Long drillId, Long checkId, CheckCommand command,
                                           Long tenantId, Long actorId) {
        if (command == null || command.status() == null || command.status() == RecoveryCheckStatus.PENDING) {
            throw new BusinessException("检查结果必须是通过、警告或失败");
        }
        String evidence = normalizeNullable(command.evidence());
        if (evidence == null || evidence.length() < 5) {
            throw new BusinessException("演练检查必须提供至少 5 个字符的验证证据");
        }
        RecoveryDrill drill = requireDrill(drillId, tenantId);
        if (drill.getStatus() != RecoveryDrillStatus.RUNNING) {
            throw new BusinessException("只有执行中的演练可以更新检查项");
        }
        RecoveryDrillCheck check = checkMapper.selectOne(new LambdaQueryWrapper<RecoveryDrillCheck>()
                .eq(RecoveryDrillCheck::getId, checkId)
                .eq(RecoveryDrillCheck::getDrillId, drillId)
                .eq(RecoveryDrillCheck::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (check == null) throw new BusinessException("演练检查项不存在");
        RecoveryCheckStatus before = check.getStatus();
        LocalDateTime now = LocalDateTime.now();
        check.setStatus(command.status());
        check.setDetail(normalizeNullable(command.detail()));
        check.setEvidence(evidence);
        check.setUpdatedBy(actorId);
        check.setCheckedAt(now);
        check.setUpdatedAt(now);
        checkMapper.updateById(check);
        auditLogService.record(tenantId, actorId, "RECOVERY_DRILL_CHECK", checkId,
                "RECOVERY_DRILL_CHECK_UPDATED", Map.of("status", before), check,
                "记录恢复演练检查证据");
        return check;
    }

    @Transactional
    public DrillDetail completeDrill(Long id, CompleteCommand command, Long tenantId, Long actorId) {
        RecoveryDrill drill = requireDrill(id, tenantId);
        if (drill.getStatus() != RecoveryDrillStatus.RUNNING) {
            throw new BusinessException("只有执行中的演练可以完成");
        }
        String evidence = normalize(command == null ? null : command.evidenceNote());
        if (evidence.length() < 5) throw new BusinessException("完成演练必须填写至少 5 个字符的恢复证据");
        List<RecoveryDrillCheck> checks = checks(id, tenantId);
        List<RecoveryDrillCheck> required = checks.stream().filter(item -> Boolean.TRUE.equals(item.getRequiredCheck())).toList();
        if (required.stream().anyMatch(item -> item.getStatus() == RecoveryCheckStatus.PENDING)) {
            throw new BusinessException("请先完成全部必需检查项");
        }
        BackupSnapshot snapshot = requireSnapshot(drill.getBackupSnapshotId(), tenantId);
        LocalDateTime completedAt = LocalDateTime.now();
        int achievedRto = elapsedMinutes(drill.getStartedAt(), completedAt);
        int achievedRpo = elapsedMinutes(snapshot.getRecoveryPointAt(), drill.getStartedAt());
        long passedChecks = required.stream().filter(item -> item.getStatus() == RecoveryCheckStatus.PASSED).count();
        int integrityScore = required.isEmpty() ? 100 : (int) Math.round(passedChecks * 100.0 / required.size());
        boolean checksPassed = passedChecks == required.size();
        boolean rpoPassed = achievedRpo <= drill.getTargetRpoMinutes();
        boolean rtoPassed = achievedRto <= drill.getTargetRtoMinutes();
        boolean passed = checksPassed && rpoPassed && rtoPassed;
        drill.setStatus(passed ? RecoveryDrillStatus.PASSED : RecoveryDrillStatus.FAILED);
        drill.setCompletedAt(completedAt);
        drill.setCompletedBy(actorId);
        drill.setAchievedRpoMinutes(achievedRpo);
        drill.setAchievedRtoMinutes(achievedRto);
        drill.setIntegrityScore(integrityScore);
        drill.setEvidenceNote(evidence);
        drill.setFindings(normalizeNullable(command.findings()));
        drill.setFailureReason(passed ? null : failureSummary(checksPassed, rpoPassed, rtoPassed));
        drill.setUpdatedAt(completedAt);
        updateDrill(drill);
        auditLogService.record(tenantId, actorId, "RECOVERY_DRILL", id,
                passed ? "RECOVERY_DRILL_PASSED" : "RECOVERY_DRILL_FAILED", RecoveryDrillStatus.RUNNING,
                drill, passed ? "恢复演练通过 RPO、RTO 与完整性门禁" : "恢复演练未通过全部门禁");
        return drillDetail(id, tenantId);
    }

    @Transactional
    public RecoveryDrill cancelDrill(Long id, String reason, Long tenantId, Long actorId) {
        RecoveryDrill drill = requireDrill(id, tenantId);
        if (drill.getStatus() != RecoveryDrillStatus.PLANNED
                && drill.getStatus() != RecoveryDrillStatus.RUNNING) {
            throw new BusinessException("当前演练不能取消");
        }
        String normalizedReason = normalize(reason);
        if (normalizedReason.length() < 5) throw new BusinessException("取消原因至少需要 5 个字符");
        RecoveryDrillStatus before = drill.getStatus();
        drill.setStatus(RecoveryDrillStatus.CANCELLED);
        drill.setFailureReason(normalizedReason);
        drill.setCompletedAt(LocalDateTime.now());
        drill.setCompletedBy(actorId);
        drill.setUpdatedAt(LocalDateTime.now());
        updateDrill(drill);
        auditLogService.record(tenantId, actorId, "RECOVERY_DRILL", id,
                "RECOVERY_DRILL_CANCELLED", before, drill, normalizedReason);
        return drill;
    }

    @Transactional
    public int expireSnapshots(Long tenantId) {
        EffectivePolicy policy = effectivePolicy(tenantId);
        if (!policy.enabled()) return 0;
        LocalDateTime now = LocalDateTime.now();
        return snapshotMapper.expireBefore(tenantId, now.minusDays(policy.retentionDays()), now);
    }

    private ReadinessSummary readiness(EffectivePolicy policy, List<BackupSnapshot> snapshots,
                                       List<RecoveryDrill> drills) {
        LocalDateTime now = LocalDateTime.now();
        List<BackupSnapshot> available = snapshots.stream()
                .filter(item -> item.getStatus() == BackupStatus.AVAILABLE)
                .filter(item -> item.getCompletedAt() != null
                        && !item.getCompletedAt().isBefore(now.minusDays(policy.retentionDays())))
                .toList();
        BackupSnapshot latest = available.stream().findFirst().orElse(null);
        long age = latest == null ? -1 : Math.max(0, ChronoUnit.MINUTES.between(latest.getRecoveryPointAt(), now));
        long overdueVerification = available.stream().filter(item -> item.getVerifiedAt() == null
                || item.getVerifiedAt().isBefore(now.minusHours(policy.verificationIntervalHours()))).count();
        long completedDrills = drills.stream().filter(item -> item.getStatus() == RecoveryDrillStatus.PASSED
                || item.getStatus() == RecoveryDrillStatus.FAILED).count();
        long passedDrills = drills.stream().filter(item -> item.getStatus() == RecoveryDrillStatus.PASSED).count();
        double passRate = completedDrills == 0 ? 0 : passedDrills * 100.0 / completedDrills;
        String state;
        String reason;
        if (!policy.enabled()) {
            state = "DISABLED";
            reason = "灾备策略已停用";
        } else if (latest == null) {
            state = "CRITICAL";
            reason = "尚未登记可用备份";
        } else if (latest.getVerificationStatus() == BackupVerificationStatus.FAILED
                || age > (long) policy.rpoMinutes() * 2) {
            state = "CRITICAL";
            reason = latest.getVerificationStatus() == BackupVerificationStatus.FAILED
                    ? "最近备份未通过策略校验" : "恢复点已严重超出 RPO";
        } else if (age > policy.rpoMinutes() || overdueVerification > 0
                || latest.getVerificationStatus() != BackupVerificationStatus.PASSED) {
            state = "WARNING";
            reason = age > policy.rpoMinutes() ? "最新恢复点已超出 RPO"
                    : overdueVerification > 0 ? "存在超期未验证备份" : "最近备份存在校验警告";
        } else {
            state = "HEALTHY";
            reason = "最新备份满足 RPO 与安全策略";
        }
        return new ReadinessSummary(state, reason, latest == null ? null : latest.getId(), age,
                available.size(), overdueVerification, completedDrills, passedDrills, passRate);
    }

    private void applyVerification(BackupSnapshot snapshot, EffectivePolicy policy, LocalDateTime verifiedAt) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long backupLag = Math.max(0, ChronoUnit.MINUTES.between(snapshot.getRecoveryPointAt(), snapshot.getCompletedAt()));
        if (!SHA256.matcher(snapshot.getChecksumSha256()).matches()) failures.add("SHA-256 校验值格式无效");
        if (snapshot.getSizeBytes() == null || snapshot.getSizeBytes() <= 0) failures.add("备份大小无效");
        if (backupLag > policy.rpoMinutes()) failures.add("备份恢复点间隔超出 RPO");
        if (policy.requireEncryption() && !Boolean.TRUE.equals(snapshot.getEncrypted())) failures.add("备份未加密");
        if (policy.requireImmutableCopy() && (snapshot.getImmutableUntil() == null
                || snapshot.getImmutableUntil().isBefore(snapshot.getCompletedAt().plusDays(policy.retentionDays())))) {
            failures.add("不可变保留期未覆盖策略要求");
        }
        if (policy.requireCrossRegionCopy() && !Boolean.TRUE.equals(snapshot.getCrossRegionCopy())) {
            failures.add("未完成跨区域副本");
        }
        if (snapshot.getEvidenceNote() == null || snapshot.getEvidenceNote().isBlank()) {
            warnings.add("未附加外部备份作业证据");
        }
        BackupVerificationStatus status = !failures.isEmpty() ? BackupVerificationStatus.FAILED
                : !warnings.isEmpty() ? BackupVerificationStatus.WARNING : BackupVerificationStatus.PASSED;
        List<String> detail = new ArrayList<>();
        detail.addAll(failures);
        detail.addAll(warnings);
        if (detail.isEmpty()) detail.add("校验和、RPO、加密与副本策略均已通过");
        snapshot.setVerificationStatus(status);
        snapshot.setVerificationDetail(String.join("；", detail));
        snapshot.setVerifiedAt(verifiedAt);
        snapshot.setUpdatedAt(verifiedAt);
    }

    private void seedChecks(RecoveryDrill drill, BackupSnapshot snapshot, EffectivePolicy policy,
                            LocalDateTime now) {
        addCheck(drill, "ARTIFACT_AVAILABLE", "备份制品可访问", true,
                snapshot.getStatus() == BackupStatus.AVAILABLE ? RecoveryCheckStatus.PASSED : RecoveryCheckStatus.FAILED,
                "备份状态：" + snapshot.getStatus(), 10, now);
        addCheck(drill, "BACKUP_POLICY", "备份策略与校验和", true,
                snapshot.getVerificationStatus() == BackupVerificationStatus.PASSED
                        ? RecoveryCheckStatus.PASSED : RecoveryCheckStatus.FAILED,
                snapshot.getVerificationDetail(), 20, now);
        addCheck(drill, "ENCRYPTION", "静态加密验证", true,
                !policy.requireEncryption() || Boolean.TRUE.equals(snapshot.getEncrypted())
                        ? RecoveryCheckStatus.PASSED : RecoveryCheckStatus.FAILED,
                Boolean.TRUE.equals(snapshot.getEncrypted()) ? "备份已加密" : "备份未标记为加密", 30, now);
        addCheck(drill, "IMMUTABILITY", "不可变副本验证", true,
                !policy.requireImmutableCopy() || (snapshot.getImmutableUntil() != null
                        && snapshot.getImmutableUntil().isAfter(now))
                        ? RecoveryCheckStatus.PASSED : RecoveryCheckStatus.FAILED,
                snapshot.getImmutableUntil() == null ? "未配置不可变期限"
                        : "不可变保护至 " + snapshot.getImmutableUntil(), 40, now);
        boolean databaseScope = snapshot.getScope() == BackupScope.FULL || snapshot.getScope() == BackupScope.DATABASE;
        boolean objectScope = snapshot.getScope() == BackupScope.FULL || snapshot.getScope() == BackupScope.OBJECT_STORAGE;
        addCheck(drill, "DATABASE_INTEGRITY", "数据库恢复与一致性校验", databaseScope,
                databaseScope ? RecoveryCheckStatus.PENDING : RecoveryCheckStatus.PASSED,
                databaseScope ? "等待在隔离数据库中恢复并校验" : "当前备份范围不包含数据库", 50, now);
        addCheck(drill, "OBJECT_INTEGRITY", "对象存储清单校验", objectScope,
                objectScope ? RecoveryCheckStatus.PENDING : RecoveryCheckStatus.PASSED,
                objectScope ? "等待比对对象数量、版本与校验值" : "当前备份范围不包含对象存储", 60, now);
        addCheck(drill, "APPLICATION_SMOKE", "核心业务冒烟验证", true, RecoveryCheckStatus.PENDING,
                "等待验证登录、工单查询、附件读取和审计链路", 70, now);
    }

    private void addCheck(RecoveryDrill drill, String key, String label, boolean required,
                          RecoveryCheckStatus status, String detail, int order, LocalDateTime now) {
        RecoveryDrillCheck check = new RecoveryDrillCheck();
        check.setTenantId(drill.getTenantId());
        check.setDrillId(drill.getId());
        check.setCheckKey(key);
        check.setLabel(label);
        check.setStatus(status);
        check.setRequiredCheck(required);
        check.setDetail(detail);
        check.setSortOrder(order);
        check.setCheckedAt(status == RecoveryCheckStatus.PENDING ? null : now);
        check.setCreatedAt(now);
        check.setUpdatedAt(now);
        checkMapper.insert(check);
    }

    private List<RecoveryDrillCheck> checks(Long drillId, Long tenantId) {
        return checkMapper.selectList(new LambdaQueryWrapper<RecoveryDrillCheck>()
                .eq(RecoveryDrillCheck::getTenantId, tenantId)
                .eq(RecoveryDrillCheck::getDrillId, drillId)
                .orderByAsc(RecoveryDrillCheck::getSortOrder));
    }

    private BackupSnapshot requireSnapshot(Long id, Long tenantId) {
        BackupSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<BackupSnapshot>()
                .eq(BackupSnapshot::getId, id).eq(BackupSnapshot::getTenantId, tenantId).last("LIMIT 1"));
        if (snapshot == null) throw new BusinessException("备份记录不存在");
        return snapshot;
    }

    private RecoveryDrill requireDrill(Long id, Long tenantId) {
        RecoveryDrill drill = drillMapper.selectOne(new LambdaQueryWrapper<RecoveryDrill>()
                .eq(RecoveryDrill::getId, id).eq(RecoveryDrill::getTenantId, tenantId).last("LIMIT 1"));
        if (drill == null) throw new BusinessException("恢复演练不存在");
        return drill;
    }

    private void updateDrill(RecoveryDrill drill) {
        if (drillMapper.updateById(drill) != 1) {
            throw new BusinessException("演练状态已被其他管理员更新，请刷新后重试");
        }
    }

    private void validatePolicy(PolicyCommand command) {
        if (command == null) throw new BusinessException("灾备策略不能为空");
        range(command.rpoMinutes(), 5, 10080, "RPO");
        range(command.rtoMinutes(), 5, 10080, "RTO");
        range(command.retentionDays(), 1, 3650, "备份保留天数");
        range(command.verificationIntervalHours(), 1, 720, "校验周期");
        if (command.requireEncryption() == null || command.requireImmutableCopy() == null
                || command.requireCrossRegionCopy() == null || command.enabled() == null) {
            throw new BusinessException("灾备策略开关不能为空");
        }
    }

    private void validateBackup(BackupCommand command) {
        if (command == null || command.scope() == null) throw new BusinessException("备份信息不完整");
        if (normalize(command.storageProvider()).length() > 64) throw new BusinessException("存储提供方长度不能超过 64");
        String storageLocation = normalize(command.storageLocation());
        if (storageLocation.length() > 500) throw new BusinessException("存储位置长度不能超过 500");
        String lowerLocation = storageLocation.toLowerCase(Locale.ROOT);
        if (storageLocation.contains("?") || storageLocation.contains("#")
                || lowerLocation.matches("^[a-z][a-z0-9+.-]*://[^/]*@.*")
                || lowerLocation.matches(".*(secret|password|token|access[_-]?key|signature)=.*")) {
            throw new BusinessException("存储位置不能包含访问凭据、查询签名或 fragment");
        }
        if (normalize(command.region()).length() > 64) throw new BusinessException("区域长度不能超过 64");
        if (command.recoveryPointAt() == null || command.capturedAt() == null || command.completedAt() == null) {
            throw new BusinessException("备份时间信息不完整");
        }
        if (command.recoveryPointAt().isAfter(command.completedAt())
                || command.capturedAt().isAfter(command.completedAt())) {
            throw new BusinessException("恢复点和采集时间不能晚于备份完成时间");
        }
        if (command.completedAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new BusinessException("备份完成时间不能晚于当前时间");
        }
        if (command.sizeBytes() == null || command.sizeBytes() <= 0) throw new BusinessException("备份大小必须大于 0");
        if (command.checksumSha256() == null || !SHA256.matcher(command.checksumSha256().trim()).matches()) {
            throw new BusinessException("必须提供 64 位 SHA-256 校验值");
        }
        if (command.encrypted() == null || command.crossRegionCopy() == null) {
            throw new BusinessException("备份安全属性不能为空");
        }
        if (command.evidenceNote() != null && command.evidenceNote().trim().length() > 1000) {
            throw new BusinessException("备份证据说明不能超过 1000 个字符");
        }
    }

    private void validateDrill(DrillCommand command) {
        if (command == null || command.backupSnapshotId() == null) throw new BusinessException("请选择演练使用的备份");
        if (normalize(command.name()).length() < 2 || normalize(command.name()).length() > 160) {
            throw new BusinessException("演练名称长度必须在 2-160 个字符之间");
        }
        String environment = normalize(command.environment());
        if (environment.length() < 2 || environment.length() > 64) {
            throw new BusinessException("隔离环境名称长度必须在 2-64 个字符之间");
        }
        String normalizedEnvironment = environment.toLowerCase(Locale.ROOT).replace("_", "-");
        if (normalizedEnvironment.startsWith("prod") || normalizedEnvironment.contains("生产")) {
            throw new BusinessException("恢复演练必须使用隔离环境，不能标记为生产环境");
        }
    }

    private void range(Integer value, int min, int max, String label) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(label + "必须在 " + min + " 到 " + max + " 之间");
        }
    }

    private int elapsedMinutes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return 0;
        // MySQL DATETIME has second precision and some drivers round fractional values. Clamp a
        // completed sub-minute operation (or tiny clock skew) to one minute instead of reporting 0.
        long seconds = Math.max(0, Duration.between(start, end).getSeconds());
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (seconds + 59) / 60));
    }

    private String failureSummary(boolean checks, boolean rpo, boolean rto) {
        List<String> failures = new ArrayList<>();
        if (!checks) failures.add("存在未通过的必需检查项");
        if (!rpo) failures.add("实际 RPO 超出目标");
        if (!rto) failures.add("实际 RTO 超出目标");
        return String.join("；", failures);
    }

    private String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("必填字段不能为空");
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private EffectivePolicy toEffective(DisasterRecoveryPolicy policy, boolean defaults) {
        return new EffectivePolicy(policy.getId(), policy.getTenantId(), policy.getRpoMinutes(),
                policy.getRtoMinutes(), policy.getRetentionDays(), policy.getVerificationIntervalHours(),
                Boolean.TRUE.equals(policy.getRequireEncryption()), Boolean.TRUE.equals(policy.getRequireImmutableCopy()),
                Boolean.TRUE.equals(policy.getRequireCrossRegionCopy()), Boolean.TRUE.equals(policy.getEnabled()),
                defaults, policy.getUpdatedAt());
    }

    public record PolicyCommand(Integer rpoMinutes, Integer rtoMinutes, Integer retentionDays,
                                Integer verificationIntervalHours, Boolean requireEncryption,
                                Boolean requireImmutableCopy, Boolean requireCrossRegionCopy, Boolean enabled) { }

    public record BackupCommand(BackupScope scope, String storageProvider, String storageLocation,
                                String region, LocalDateTime recoveryPointAt, LocalDateTime capturedAt,
                                LocalDateTime completedAt, Long sizeBytes, String checksumSha256,
                                Boolean encrypted, LocalDateTime immutableUntil, Boolean crossRegionCopy,
                                String evidenceNote) { }

    public record DrillCommand(String name, Long backupSnapshotId, String environment,
                               LocalDateTime scheduledAt) { }

    public record CheckCommand(RecoveryCheckStatus status, String detail, String evidence) { }

    public record CompleteCommand(String evidenceNote, String findings) { }

    public record EffectivePolicy(Long id, Long tenantId, int rpoMinutes, int rtoMinutes,
                                  int retentionDays, int verificationIntervalHours,
                                  boolean requireEncryption, boolean requireImmutableCopy,
                                  boolean requireCrossRegionCopy, boolean enabled,
                                  boolean inheritedDefaults, LocalDateTime updatedAt) { }

    public record ReadinessSummary(String state, String reason, Long latestBackupId,
                                   long latestRecoveryPointAgeMinutes, long availableBackups,
                                   long overdueVerifications, long completedDrills,
                                   long passedDrills, double drillPassRate) { }

    public record RecoveryCenterView(EffectivePolicy policy, ReadinessSummary summary,
                                     List<BackupSnapshot> snapshots, List<RecoveryDrill> drills) { }

    public record DrillDetail(RecoveryDrill drill, BackupSnapshot snapshot,
                              List<RecoveryDrillCheck> checks) { }
}
