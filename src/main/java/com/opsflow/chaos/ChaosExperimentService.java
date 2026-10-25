package com.opsflow.chaos;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChaosExperimentService {
    private final ChaosExperimentMapper experimentMapper;
    private final ChaosExperimentCheckMapper checkMapper;
    private final AuditLogService auditLogService;

    public ChaosCenterView center(Long tenantId) {
        List<ChaosExperiment> experiments = experimentMapper.selectList(
                new LambdaQueryWrapper<ChaosExperiment>()
                        .eq(ChaosExperiment::getTenantId, tenantId)
                        .orderByDesc(ChaosExperiment::getCreatedAt)
                        .orderByDesc(ChaosExperiment::getId)
                        .last("LIMIT 100"));
        long active = experiments.stream().filter(item -> List.of(ChaosExperimentStatus.BASELINE,
                ChaosExperimentStatus.FAULT_INJECTED, ChaosExperimentStatus.RECOVERING).contains(item.getStatus())).count();
        long completed = experiments.stream().filter(item -> List.of(ChaosExperimentStatus.PASSED,
                ChaosExperimentStatus.FAILED).contains(item.getStatus())).count();
        long passed = experiments.stream().filter(item -> item.getStatus() == ChaosExperimentStatus.PASSED).count();
        double passRate = completed == 0 ? 0 : Math.round(passed * 1000.0 / completed) / 10.0;
        return new ChaosCenterView(new ChaosSummary(experiments.size(), active, completed, passed, passRate), experiments);
    }

    public ExperimentDetail detail(Long id, Long tenantId) {
        ChaosExperiment experiment = requireExperiment(id, tenantId);
        List<ChaosExperimentCheck> checks = checks(id, tenantId);
        return new ExperimentDetail(experiment, checks, playbook(experiment.getTarget()));
    }

    @Transactional
    public ExperimentDetail create(CreateCommand command, Long tenantId, Long actorId) {
        validateCreate(command);
        LocalDateTime now = LocalDateTime.now();
        ChaosExperiment experiment = new ChaosExperiment();
        experiment.setTenantId(tenantId);
        experiment.setExperimentNo("CH-" + token());
        experiment.setName(normalize(command.name()));
        experiment.setTarget(command.target());
        experiment.setEnvironment(normalize(command.environment()));
        experiment.setStatus(ChaosExperimentStatus.PLANNED);
        experiment.setVersion(0);
        experiment.setHypothesis(normalize(command.hypothesis()));
        experiment.setRollbackPlan(normalize(command.rollbackPlan()));
        experiment.setTargetRecoveryMinutes(command.targetRecoveryMinutes());
        experiment.setCreatedBy(actorId);
        experiment.setUpdatedBy(actorId);
        experiment.setCreatedAt(now);
        experiment.setUpdatedAt(now);
        experimentMapper.insert(experiment);
        seedChecks(experiment, now);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT", experiment.getId(),
                "CHAOS_EXPERIMENT_CREATED", null, experiment,
                "创建非生产关键链路混沌演练；平台不执行基础设施破坏命令");
        return detail(experiment.getId(), tenantId);
    }

    @Transactional
    public ExperimentDetail start(Long id, Long tenantId, Long actorId) {
        ChaosExperiment experiment = requireExperiment(id, tenantId);
        requireStatus(experiment, ChaosExperimentStatus.PLANNED, "只有待执行实验可以开始");
        LocalDateTime now = LocalDateTime.now();
        experiment.setStatus(ChaosExperimentStatus.BASELINE);
        experiment.setStartedAt(now);
        touch(experiment, actorId, now);
        update(experiment);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT", id,
                "CHAOS_EXPERIMENT_STARTED", ChaosExperimentStatus.PLANNED, experiment,
                "开始采集故障前基线证据");
        return detail(id, tenantId);
    }

    @Transactional
    public ExperimentDetail confirmFaultInjected(Long id, Long tenantId, Long actorId) {
        ChaosExperiment experiment = requireExperiment(id, tenantId);
        requireStatus(experiment, ChaosExperimentStatus.BASELINE, "请先开始实验并完成基线检查");
        requirePhaseReady(id, tenantId, ChaosCheckPhase.BASELINE, true);
        LocalDateTime now = LocalDateTime.now();
        experiment.setStatus(ChaosExperimentStatus.FAULT_INJECTED);
        experiment.setFaultStartedAt(now);
        touch(experiment, actorId, now);
        update(experiment);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT", id,
                "CHAOS_FAULT_CONFIRMED", ChaosExperimentStatus.BASELINE, experiment,
                "操作者确认已在隔离环境注入故障；OpsFlow 未执行破坏命令");
        return detail(id, tenantId);
    }

    @Transactional
    public ExperimentDetail beginRecovery(Long id, Long tenantId, Long actorId) {
        ChaosExperiment experiment = requireExperiment(id, tenantId);
        requireStatus(experiment, ChaosExperimentStatus.FAULT_INJECTED, "只有故障观察阶段可以进入恢复");
        requirePhaseReady(id, tenantId, ChaosCheckPhase.FAULT, false);
        LocalDateTime now = LocalDateTime.now();
        experiment.setStatus(ChaosExperimentStatus.RECOVERING);
        experiment.setRecoveryStartedAt(now);
        touch(experiment, actorId, now);
        update(experiment);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT", id,
                "CHAOS_RECOVERY_STARTED", ChaosExperimentStatus.FAULT_INJECTED, experiment,
                "操作者确认已执行回滚方案，开始恢复验证");
        return detail(id, tenantId);
    }

    @Transactional
    public ChaosExperimentCheck updateCheck(Long experimentId, Long checkId, CheckCommand command,
                                             Long tenantId, Long actorId) {
        if (command == null || command.status() == null || command.status() == ChaosCheckStatus.PENDING) {
            throw new BusinessException("请选择通过、警告或失败");
        }
        String evidence = normalize(command.evidence());
        if (evidence.length() < 5 || evidence.length() > 1000) {
            throw new BusinessException("证据长度必须在 5-1000 个字符之间");
        }
        if (command.detail() != null && command.detail().trim().length() > 1000) {
            throw new BusinessException("检查结论不能超过 1000 个字符");
        }
        ChaosExperiment experiment = requireExperiment(experimentId, tenantId);
        if (!List.of(ChaosExperimentStatus.BASELINE, ChaosExperimentStatus.FAULT_INJECTED,
                ChaosExperimentStatus.RECOVERING).contains(experiment.getStatus())) {
            throw new BusinessException("只有执行中的实验可以记录检查证据");
        }
        ChaosExperimentCheck check = checkMapper.selectOne(new LambdaQueryWrapper<ChaosExperimentCheck>()
                .eq(ChaosExperimentCheck::getId, checkId)
                .eq(ChaosExperimentCheck::getExperimentId, experimentId)
                .eq(ChaosExperimentCheck::getTenantId, tenantId));
        if (check == null) throw new BusinessException("实验检查项不存在");
        if (!phaseAvailable(experiment.getStatus(), check.getPhase())) {
            throw new BusinessException("当前实验阶段不能记录该检查项");
        }
        ChaosCheckStatus before = check.getStatus();
        LocalDateTime now = LocalDateTime.now();
        check.setStatus(command.status());
        check.setDetail(normalizeNullable(command.detail()));
        check.setEvidence(evidence);
        check.setUpdatedBy(actorId);
        check.setCheckedAt(now);
        check.setUpdatedAt(now);
        checkMapper.updateById(check);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT_CHECK", checkId,
                "CHAOS_CHECK_RECORDED", Map.of("status", before), check,
                "记录关键链路混沌实验证据");
        return check;
    }

    @Transactional
    public ExperimentDetail complete(Long id, CompleteCommand command, Long tenantId, Long actorId) {
        ChaosExperiment experiment = requireExperiment(id, tenantId);
        requireStatus(experiment, ChaosExperimentStatus.RECOVERING, "只有恢复验证阶段可以完成实验");
        requirePhaseReady(id, tenantId, ChaosCheckPhase.RECOVERY, false);
        String findings = normalize(command == null ? null : command.findings());
        if (findings.length() < 5 || findings.length() > 2000) {
            throw new BusinessException("复盘结论长度必须在 5-2000 个字符之间");
        }
        List<ChaosExperimentCheck> checks = checks(id, tenantId);
        boolean checksPassed = checks.stream().filter(item -> Boolean.TRUE.equals(item.getRequiredCheck()))
                .noneMatch(item -> item.getStatus() == ChaosCheckStatus.FAILED
                        || item.getStatus() == ChaosCheckStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        long recoverySeconds = Math.max(1, Duration.between(experiment.getFaultStartedAt(), now).getSeconds());
        boolean withinTarget = recoverySeconds <= experiment.getTargetRecoveryMinutes() * 60L;
        experiment.setAchievedRecoverySeconds(recoverySeconds);
        experiment.setCompletedAt(now);
        experiment.setFindings(findings);
        experiment.setStatus(checksPassed && withinTarget ? ChaosExperimentStatus.PASSED : ChaosExperimentStatus.FAILED);
        touch(experiment, actorId, now);
        update(experiment);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT", id,
                "CHAOS_EXPERIMENT_COMPLETED", ChaosExperimentStatus.RECOVERING, experiment,
                checksPassed && withinTarget ? "混沌实验门禁与恢复目标通过" : "混沌实验存在失败门禁或恢复超时");
        return detail(id, tenantId);
    }

    @Transactional
    public ChaosExperiment cancel(Long id, String reason, Long tenantId, Long actorId) {
        ChaosExperiment experiment = requireExperiment(id, tenantId);
        if (List.of(ChaosExperimentStatus.PASSED, ChaosExperimentStatus.FAILED,
                ChaosExperimentStatus.CANCELLED).contains(experiment.getStatus())) {
            throw new BusinessException("已结束实验不能取消");
        }
        String normalizedReason = normalize(reason);
        if (normalizedReason.length() < 5 || normalizedReason.length() > 1000) {
            throw new BusinessException("取消原因长度必须在 5-1000 个字符之间");
        }
        ChaosExperimentStatus before = experiment.getStatus();
        LocalDateTime now = LocalDateTime.now();
        experiment.setStatus(ChaosExperimentStatus.CANCELLED);
        experiment.setCancellationReason(normalizedReason);
        experiment.setCompletedAt(now);
        touch(experiment, actorId, now);
        update(experiment);
        auditLogService.record(tenantId, actorId, "CHAOS_EXPERIMENT", id,
                "CHAOS_EXPERIMENT_CANCELLED", before, experiment, "取消实验：" + normalizedReason);
        return experiment;
    }

    private void seedChecks(ChaosExperiment experiment, LocalDateTime now) {
        List<CheckSeed> seeds = List.of(
                new CheckSeed(ChaosCheckPhase.BASELINE, "BASELINE_HEALTH", "故障前关键链路健康", true, 10),
                new CheckSeed(ChaosCheckPhase.FAULT, "FAULT_INJECTION_EVIDENCE", "隔离环境故障注入证据", true, 20),
                new CheckSeed(ChaosCheckPhase.FAULT, "FALLBACK_BEHAVIOR", "降级或容错行为符合预期", true, 30),
                new CheckSeed(ChaosCheckPhase.FAULT, "ALERT_OBSERVED", "告警与值班链路被触发", true, 40),
                new CheckSeed(ChaosCheckPhase.RECOVERY, "RECOVERY_HEALTH", "依赖恢复后关键链路健康", true, 50),
                new CheckSeed(ChaosCheckPhase.RECOVERY, "DATA_INTEGRITY", "数据一致性与幂等性通过", true, 60));
        for (CheckSeed seed : seeds) {
            ChaosExperimentCheck check = new ChaosExperimentCheck();
            check.setTenantId(experiment.getTenantId());
            check.setExperimentId(experiment.getId());
            check.setPhase(seed.phase());
            check.setCheckKey(seed.key());
            check.setLabel(seed.label());
            check.setStatus(ChaosCheckStatus.PENDING);
            check.setRequiredCheck(seed.required());
            check.setSortOrder(seed.order());
            check.setCreatedAt(now);
            check.setUpdatedAt(now);
            checkMapper.insert(check);
        }
    }

    private void requirePhaseReady(Long experimentId, Long tenantId, ChaosCheckPhase phase, boolean rejectFailure) {
        List<ChaosExperimentCheck> phaseChecks = checks(experimentId, tenantId).stream()
                .filter(item -> item.getPhase() == phase && Boolean.TRUE.equals(item.getRequiredCheck())).toList();
        if (phaseChecks.isEmpty() || phaseChecks.stream().anyMatch(item -> item.getStatus() == ChaosCheckStatus.PENDING)) {
            throw new BusinessException("请先完成当前阶段的全部必需检查项");
        }
        if (rejectFailure && phaseChecks.stream().anyMatch(item -> item.getStatus() == ChaosCheckStatus.FAILED)) {
            throw new BusinessException("基线检查失败，禁止注入故障");
        }
    }

    private boolean phaseAvailable(ChaosExperimentStatus status, ChaosCheckPhase phase) {
        return switch (status) {
            case BASELINE -> phase == ChaosCheckPhase.BASELINE;
            case FAULT_INJECTED -> phase == ChaosCheckPhase.FAULT;
            case RECOVERING -> phase == ChaosCheckPhase.RECOVERY;
            default -> false;
        };
    }

    private List<ChaosExperimentCheck> checks(Long experimentId, Long tenantId) {
        return checkMapper.selectList(new LambdaQueryWrapper<ChaosExperimentCheck>()
                .eq(ChaosExperimentCheck::getTenantId, tenantId)
                .eq(ChaosExperimentCheck::getExperimentId, experimentId)
                .orderByAsc(ChaosExperimentCheck::getSortOrder)
                .orderByAsc(ChaosExperimentCheck::getId));
    }

    private ChaosExperiment requireExperiment(Long id, Long tenantId) {
        ChaosExperiment experiment = experimentMapper.selectOne(new LambdaQueryWrapper<ChaosExperiment>()
                .eq(ChaosExperiment::getId, id)
                .eq(ChaosExperiment::getTenantId, tenantId));
        if (experiment == null) throw new BusinessException("混沌实验不存在或无权访问");
        return experiment;
    }

    private void requireStatus(ChaosExperiment experiment, ChaosExperimentStatus expected, String message) {
        if (experiment.getStatus() != expected) throw new BusinessException(message);
    }

    private void validateCreate(CreateCommand command) {
        if (command == null || command.target() == null) throw new BusinessException("请选择实验目标");
        String name = normalize(command.name());
        String environment = normalize(command.environment());
        String normalizedEnvironment = environment.toLowerCase(Locale.ROOT).replace("_", "-");
        if (name.length() < 2 || name.length() > 160) throw new BusinessException("实验名称长度必须在 2-160 个字符之间");
        if (environment.length() < 2 || environment.length() > 64) throw new BusinessException("环境名称长度必须在 2-64 个字符之间");
        if (normalizedEnvironment.startsWith("prod") || normalizedEnvironment.contains("production")
                || normalizedEnvironment.contains("生产") || normalizedEnvironment.equals("live")) {
            throw new BusinessException("混沌实验禁止使用生产环境");
        }
        if (normalize(command.hypothesis()).length() > 1000) throw new BusinessException("稳态假设不能超过 1000 个字符");
        if (normalize(command.rollbackPlan()).length() > 1000) throw new BusinessException("回滚方案不能超过 1000 个字符");
        if (command.targetRecoveryMinutes() == null || command.targetRecoveryMinutes() < 1
                || command.targetRecoveryMinutes() > 240) {
            throw new BusinessException("恢复目标必须在 1-240 分钟之间");
        }
    }

    private Playbook playbook(ChaosTarget target) {
        return switch (target) {
            case DATABASE -> new Playbook("在隔离环境阻断应用到 MySQL 的连接",
                    "写请求快速失败且错误可观测，恢复后事务与审计数据一致", "恢复网络并验证连接池、事务与关键查询");
            case REDIS -> new Playbook("在隔离环境暂停 Redis 或阻断连接",
                    "缓存失效不破坏数据库事实数据，限流与会话异常可观测", "恢复 Redis 并验证缓存重建和业务读写");
            case RABBITMQ -> new Playbook("在隔离环境暂停 RabbitMQ 消费或 Broker",
                    "Outbox 保留消息且不丢失，积压与死信产生告警", "恢复 Broker 后验证幂等消费、积压清空和无重复副作用");
            case OBJECT_STORAGE -> new Playbook("在隔离环境阻断对象存储端点",
                    "上传失败可解释且数据库不产生悬空可用附件", "恢复端点后验证隔离区、元数据和对象一致性");
            case WEBHOOK_DELIVERY -> new Playbook("将测试 Webhook 指向可控超时端点",
                    "指数退避、失败分类和死信治理按预期工作", "恢复接收端并执行受控重放，验证幂等键");
        };
    }

    private void touch(ChaosExperiment experiment, Long actorId, LocalDateTime now) {
        experiment.setUpdatedBy(actorId);
        experiment.setUpdatedAt(now);
    }

    private void update(ChaosExperiment experiment) {
        if (experimentMapper.updateById(experiment) != 1) throw new BusinessException("实验状态已变化，请刷新后重试");
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

    private record CheckSeed(ChaosCheckPhase phase, String key, String label, boolean required, int order) { }
    public record CreateCommand(String name, ChaosTarget target, String environment, String hypothesis,
                                String rollbackPlan, Integer targetRecoveryMinutes) { }
    public record CheckCommand(ChaosCheckStatus status, String detail, String evidence) { }
    public record CompleteCommand(String findings) { }
    public record ChaosSummary(long total, long active, long completed, long passed, double passRate) { }
    public record ChaosCenterView(ChaosSummary summary, List<ChaosExperiment> experiments) { }
    public record Playbook(String injectionGuide, String expectedBehavior, String rollbackGuide) { }
    public record ExperimentDetail(ChaosExperiment experiment, List<ChaosExperimentCheck> checks,
                                   Playbook playbook) { }
}
