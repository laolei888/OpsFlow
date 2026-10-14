package com.opsflow.runbook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.alert.AlertSource;
import com.opsflow.alert.AlertSourceMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Human-in-the-loop runbook orchestration.  The service intentionally records
 * operational evidence only; it never executes commands against user systems.
 */
@Service
@RequiredArgsConstructor
public class RunbookService {
    private final RunbookMapper runbookMapper;
    private final RunbookStepMapper runbookStepMapper;
    private final RunbookAlertMatcherMapper alertMatcherMapper;
    private final RunbookExecutionMapper executionMapper;
    private final RunbookExecutionStepMapper executionStepMapper;
    private final MonitoringAlertMapper alertMapper;
    private final AlertSourceMapper alertSourceMapper;
    private final IncidentMapper incidentMapper;
    private final TicketMapper ticketMapper;
    private final AuditLogService auditLogService;

    public RunbookCenterView center(Long tenantId) {
        List<Runbook> runbooks = runbookMapper.selectList(new LambdaQueryWrapper<Runbook>()
                .eq(Runbook::getTenantId, tenantId)
                .orderByDesc(Runbook::getEnabled)
                .orderByAsc(Runbook::getCategory)
                .orderByAsc(Runbook::getName)
                .last("LIMIT 160"));
        List<RunbookExecution> executions = executionMapper.selectList(new LambdaQueryWrapper<RunbookExecution>()
                .eq(RunbookExecution::getTenantId, tenantId)
                .orderByAsc(RunbookExecution::getStatus)
                .orderByDesc(RunbookExecution::getStartedAt)
                .last("LIMIT 160"));
        return new RunbookCenterView(runbooks, executions, alertMatchers(tenantId), alertSources(tenantId));
    }

    public RunbookDetail detail(Long id, Long tenantId) {
        Runbook runbook = getRunbook(id, tenantId);
        return new RunbookDetail(runbook, templateSteps(id, tenantId));
    }

    public ExecutionDetail executionDetail(Long id, Long tenantId) {
        RunbookExecution execution = getExecution(id, tenantId);
        Runbook runbook = runbookMapper.selectOne(new LambdaQueryWrapper<Runbook>()
                .eq(Runbook::getId, execution.getRunbookId())
                .eq(Runbook::getTenantId, tenantId));
        return new ExecutionDetail(execution, runbook, executionSteps(id, tenantId));
    }

    public List<AlertRecommendation> recommendationsForAlert(Long alertId, Long tenantId) {
        MonitoringAlert alert = alertMapper.selectOne(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getId, alertId).eq(MonitoringAlert::getTenantId, tenantId));
        if (alert == null) throw new BusinessException("告警不存在或不属于当前租户");
        return alertMatchers(tenantId).stream()
                .filter(matcher -> Boolean.TRUE.equals(matcher.getEnabled()))
                .map(matcher -> new MatchedAlertRule(matcher, getOptionalRunbook(matcher.getRunbookId(), tenantId)))
                .filter(item -> item.runbook() != null && Boolean.TRUE.equals(item.runbook().getEnabled()))
                .filter(item -> item.runbook().getTargetType() == RunbookTargetType.ALERT
                        || item.runbook().getTargetType() == RunbookTargetType.GENERAL)
                .filter(item -> matches(item.matcher(), alert))
                .limit(5)
                .map(item -> new AlertRecommendation(item.runbook(), item.matcher(), recommendationReasons(item.matcher(), alert)))
                .toList();
    }

    @Transactional
    public RunbookAlertMatcher createAlertMatcher(Long runbookId, AlertMatcherCommand command, Long tenantId, Long actorId) {
        validateAlertMatcher(command, tenantId);
        Runbook runbook = getRunbook(runbookId, tenantId);
        ensureAlertCompatible(runbook);
        LocalDateTime now = LocalDateTime.now();
        RunbookAlertMatcher matcher = new RunbookAlertMatcher();
        matcher.setTenantId(tenantId);
        matcher.setRunbookId(runbookId);
        applyAlertMatcher(matcher, command);
        matcher.setCreatedBy(actorId);
        matcher.setUpdatedBy(actorId);
        matcher.setCreatedAt(now);
        matcher.setUpdatedAt(now);
        alertMatcherMapper.insert(matcher);
        auditLogService.record(tenantId, actorId, "RUNBOOK", runbookId, "RUNBOOK_ALERT_MATCHER_CREATED", null, matcher,
                "配置告警到运行手册的推荐规则；仅推荐，仍由人工确认发起执行");
        return matcher;
    }

    @Transactional
    public RunbookAlertMatcher updateAlertMatcher(Long matcherId, AlertMatcherCommand command, Long tenantId, Long actorId) {
        validateAlertMatcher(command, tenantId);
        RunbookAlertMatcher matcher = getAlertMatcher(matcherId, tenantId);
        Runbook runbook = getRunbook(matcher.getRunbookId(), tenantId);
        ensureAlertCompatible(runbook);
        RunbookAlertMatcher before = copyAlertMatcher(matcher);
        applyAlertMatcher(matcher, command);
        matcher.setUpdatedBy(actorId);
        matcher.setUpdatedAt(LocalDateTime.now());
        alertMatcherMapper.updateById(matcher);
        auditLogService.record(tenantId, actorId, "RUNBOOK", runbook.getId(), "RUNBOOK_ALERT_MATCHER_UPDATED", before, matcher,
                "更新告警运行手册推荐规则");
        return matcher;
    }

    @Transactional
    public Runbook create(RunbookCommand command, Long tenantId, Long actorId) {
        validateRunbook(command, true);
        String slug = normalizeSlug(command.slug());
        ensureSlugAvailable(slug, tenantId, null);
        LocalDateTime now = LocalDateTime.now();
        Runbook runbook = new Runbook();
        applyRunbook(runbook, command, slug);
        runbook.setTenantId(tenantId);
        runbook.setVersionNo(1);
        runbook.setCreatedBy(actorId);
        runbook.setUpdatedBy(actorId);
        runbook.setCreatedAt(now);
        runbook.setUpdatedAt(now);
        runbookMapper.insert(runbook);
        int index = 10;
        for (TemplateStepCommand step : command.steps()) {
            createTemplateStep(runbook.getId(), step, index, tenantId, actorId, now);
            index += 10;
        }
        auditLogService.record(tenantId, actorId, "RUNBOOK", runbook.getId(), "RUNBOOK_CREATED",
                null, runbook, "创建运行手册并生成可审计的人工处置步骤");
        return runbook;
    }

    @Transactional
    public Runbook update(Long id, RunbookCommand command, Long tenantId, Long actorId) {
        validateRunbook(command, false);
        Runbook runbook = lockRunbook(id, tenantId);
        Runbook before = copyRunbook(runbook);
        String slug = normalizeSlug(command.slug());
        ensureSlugAvailable(slug, tenantId, id);
        applyRunbook(runbook, command, slug);
        runbook.setVersionNo(runbook.getVersionNo() + 1);
        runbook.setUpdatedBy(actorId);
        runbook.setUpdatedAt(LocalDateTime.now());
        runbookMapper.updateById(runbook);
        auditLogService.record(tenantId, actorId, "RUNBOOK", id, "RUNBOOK_UPDATED", before, runbook,
                "更新运行手册元数据，后续执行将使用新版本快照");
        return runbook;
    }

    @Transactional
    public RunbookStep addTemplateStep(Long runbookId, TemplateStepCommand command, Long tenantId, Long actorId) {
        validateTemplateStep(command);
        Runbook runbook = lockRunbook(runbookId, tenantId);
        LocalDateTime now = LocalDateTime.now();
        RunbookStep step = createTemplateStep(runbookId, command, normalizeOrder(command.sortOrder()), tenantId, actorId, now);
        bumpVersion(runbook, actorId, now);
        auditLogService.record(tenantId, actorId, "RUNBOOK", runbookId, "RUNBOOK_STEP_CREATED", null, step,
                "新增运行手册步骤并递增版本");
        return step;
    }

    @Transactional
    public RunbookStep updateTemplateStep(Long stepId, TemplateStepCommand command, Long tenantId, Long actorId) {
        validateTemplateStep(command);
        RunbookStep step = getTemplateStep(stepId, tenantId);
        Runbook runbook = lockRunbook(step.getRunbookId(), tenantId);
        RunbookStep before = copyTemplateStep(step);
        applyTemplateStep(step, command, normalizeOrder(command.sortOrder()));
        step.setUpdatedAt(LocalDateTime.now());
        runbookStepMapper.updateById(step);
        bumpVersion(runbook, actorId, step.getUpdatedAt());
        auditLogService.record(tenantId, actorId, "RUNBOOK", runbook.getId(), "RUNBOOK_STEP_UPDATED", before, step,
                "更新运行手册模板步骤，历史执行快照不受影响");
        return step;
    }

    @Transactional
    public RunbookExecution startExecution(ExecutionStartCommand command, Long tenantId, Long actorId) {
        if (command == null || command.runbookId() == null || command.sourceType() == null) {
            throw new BusinessException("运行手册、处置来源不能为空");
        }
        Runbook runbook = getRunbook(command.runbookId(), tenantId);
        if (!Boolean.TRUE.equals(runbook.getEnabled())) throw new BusinessException("该运行手册已停用，不能发起处置");
        List<RunbookStep> templateSteps = templateSteps(runbook.getId(), tenantId);
        if (templateSteps.isEmpty()) throw new BusinessException("运行手册没有可执行步骤");
        if (templateSteps.size() > 60) throw new BusinessException("单个运行手册最多支持 60 个步骤");
        SourceContext context = resolveSource(command, tenantId);
        ensureTargetCompatible(runbook.getTargetType(), command.sourceType());
        LocalDateTime now = LocalDateTime.now();
        RunbookExecution execution = new RunbookExecution();
        execution.setTenantId(tenantId);
        execution.setExecutionNo("RBX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        execution.setRunbookId(runbook.getId());
        execution.setRunbookVersion(runbook.getVersionNo());
        execution.setSourceType(command.sourceType());
        execution.setSourceId(context.sourceId());
        execution.setSourceRef(context.sourceRef());
        execution.setTitle(optionalText(command.title(), "处置标题", 180, context.defaultTitle()));
        execution.setStatus(RunbookExecutionStatus.IN_PROGRESS);
        execution.setInitiatedBy(actorId);
        execution.setStartedAt(now);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        executionMapper.insert(execution);
        for (RunbookStep templateStep : templateSteps) {
            RunbookExecutionStep snapshot = new RunbookExecutionStep();
            snapshot.setTenantId(tenantId);
            snapshot.setExecutionId(execution.getId());
            snapshot.setRunbookStepId(templateStep.getId());
            snapshot.setSortOrder(templateStep.getSortOrder());
            snapshot.setTitle(templateStep.getTitle());
            snapshot.setInstruction(templateStep.getInstruction());
            snapshot.setVerificationHint(templateStep.getVerificationHint());
            snapshot.setRequiredStep(templateStep.getRequiredStep());
            snapshot.setStatus(RunbookStepStatus.PENDING);
            snapshot.setCreatedAt(now);
            snapshot.setUpdatedAt(now);
            executionStepMapper.insert(snapshot);
        }
        auditLogService.record(tenantId, actorId, "RUNBOOK_EXECUTION", execution.getId(), "RUNBOOK_EXECUTION_STARTED",
                null, execution, "发起人工运行手册处置，已固化步骤版本 " + runbook.getVersionNo());
        return execution;
    }

    @Transactional
    public RunbookExecutionStep updateExecutionStep(Long stepId, ExecutionStepCommand command, Long tenantId, Long actorId) {
        if (command == null || command.status() == null) throw new BusinessException("请选择步骤处置状态");
        RunbookExecutionStep step = getExecutionStep(stepId, tenantId);
        RunbookExecution execution = lockExecution(step.getExecutionId(), tenantId);
        ensureExecutionOpen(execution);
        if (Boolean.TRUE.equals(step.getRequiredStep()) && command.status() == RunbookStepStatus.SKIPPED) {
            throw new BusinessException("必做步骤不能跳过；请记录通过或失败的处置证据");
        }
        RunbookExecutionStep before = copyExecutionStep(step);
        step.setStatus(command.status());
        step.setEvidence(optionalText(command.evidence(), "处置证据", 2000, null));
        if (command.status() == RunbookStepStatus.PENDING) {
            step.setCompletedBy(null);
            step.setCompletedAt(null);
        } else {
            step.setCompletedBy(actorId);
            step.setCompletedAt(LocalDateTime.now());
        }
        step.setUpdatedAt(LocalDateTime.now());
        executionStepMapper.updateById(step);
        execution.setUpdatedAt(step.getUpdatedAt());
        executionMapper.updateById(execution);
        auditLogService.record(tenantId, actorId, "RUNBOOK_EXECUTION", execution.getId(), "RUNBOOK_EXECUTION_STEP_UPDATED",
                before, step, "记录处置步骤结果：" + command.status());
        return step;
    }

    @Transactional
    public RunbookExecution complete(Long executionId, String outcomeNote, Long tenantId, Long actorId) {
        RunbookExecution execution = lockExecution(executionId, tenantId);
        ensureExecutionOpen(execution);
        boolean remainingRequired = executionSteps(executionId, tenantId).stream()
                .anyMatch(step -> Boolean.TRUE.equals(step.getRequiredStep()) && step.getStatus() != RunbookStepStatus.PASSED);
        if (remainingRequired) throw new BusinessException("仍有必做步骤未通过，不能完成处置");
        return finish(execution, RunbookExecutionStatus.COMPLETED,
                optionalText(outcomeNote, "处置结论", 1000, "全部必做步骤已通过"), tenantId, actorId,
                "RUNBOOK_EXECUTION_COMPLETED", "完成运行手册处置并保留全部执行证据");
    }

    @Transactional
    public RunbookExecution fail(Long executionId, String outcomeNote, Long tenantId, Long actorId) {
        return terminate(executionId, outcomeNote, RunbookExecutionStatus.FAILED, tenantId, actorId,
                "RUNBOOK_EXECUTION_FAILED", "标记运行手册处置失败");
    }

    @Transactional
    public RunbookExecution cancel(Long executionId, String outcomeNote, Long tenantId, Long actorId) {
        return terminate(executionId, outcomeNote, RunbookExecutionStatus.CANCELLED, tenantId, actorId,
                "RUNBOOK_EXECUTION_CANCELLED", "取消运行手册处置");
    }

    private RunbookExecution terminate(Long executionId, String outcomeNote, RunbookExecutionStatus status,
                                       Long tenantId, Long actorId, String action, String detail) {
        String reason = requiredText(outcomeNote, "失败或取消原因", 1000);
        RunbookExecution execution = lockExecution(executionId, tenantId);
        ensureExecutionOpen(execution);
        return finish(execution, status, reason, tenantId, actorId, action, detail);
    }

    private RunbookExecution finish(RunbookExecution execution, RunbookExecutionStatus targetStatus, String note,
                                    Long tenantId, Long actorId, String action, String detail) {
        RunbookExecution before = copyExecution(execution);
        LocalDateTime now = LocalDateTime.now();
        execution.setStatus(targetStatus);
        execution.setOutcomeNote(note);
        execution.setCompletedBy(actorId);
        execution.setCompletedAt(now);
        execution.setUpdatedAt(now);
        executionMapper.updateById(execution);
        auditLogService.record(tenantId, actorId, "RUNBOOK_EXECUTION", execution.getId(), action, before, execution, detail);
        return execution;
    }

    private void validateRunbook(RunbookCommand command, boolean create) {
        if (command == null) throw new BusinessException("运行手册内容不能为空");
        requiredText(command.name(), "手册名称", 120);
        normalizeSlug(command.slug());
        requiredText(command.category(), "手册分类", 64);
        if (command.targetType() == null) throw new BusinessException("请选择适用对象");
        optionalText(command.description(), "手册说明", 10000, null);
        if (create) {
            if (command.steps() == null || command.steps().isEmpty()) throw new BusinessException("请至少配置一个处置步骤");
            if (command.steps().size() > 60) throw new BusinessException("单个运行手册最多支持 60 个步骤");
            command.steps().forEach(this::validateTemplateStep);
        }
    }

    private void validateTemplateStep(TemplateStepCommand command) {
        if (command == null) throw new BusinessException("运行手册步骤不能为空");
        requiredText(command.title(), "步骤标题", 180);
        requiredText(command.instruction(), "操作说明", 10000);
        optionalText(command.verificationHint(), "验证提示", 1000, null);
    }

    private RunbookStep createTemplateStep(Long runbookId, TemplateStepCommand command, int sortOrder,
                                            Long tenantId, Long actorId, LocalDateTime now) {
        RunbookStep step = new RunbookStep();
        step.setTenantId(tenantId);
        step.setRunbookId(runbookId);
        applyTemplateStep(step, command, sortOrder);
        step.setCreatedBy(actorId);
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        runbookStepMapper.insert(step);
        return step;
    }

    private void applyTemplateStep(RunbookStep target, TemplateStepCommand command, int sortOrder) {
        target.setSortOrder(sortOrder);
        target.setTitle(requiredText(command.title(), "步骤标题", 180));
        target.setInstruction(requiredText(command.instruction(), "操作说明", 10000));
        target.setVerificationHint(optionalText(command.verificationHint(), "验证提示", 1000, null));
        target.setRequiredStep(command.requiredStep() == null || command.requiredStep());
    }

    private void applyRunbook(Runbook target, RunbookCommand command, String slug) {
        target.setName(requiredText(command.name(), "手册名称", 120));
        target.setSlug(slug);
        target.setDescription(optionalText(command.description(), "手册说明", 10000, null));
        target.setCategory(requiredText(command.category(), "手册分类", 64));
        target.setTargetType(command.targetType());
        target.setEnabled(command.enabled() == null || command.enabled());
    }

    private SourceContext resolveSource(ExecutionStartCommand command, Long tenantId) {
        if (command.sourceType() == RunbookExecutionSource.MANUAL) {
            return new SourceContext(null, "MANUAL", "人工发起处置");
        }
        if (command.sourceId() == null) throw new BusinessException("请选择要关联的处置对象");
        return switch (command.sourceType()) {
            case ALERT -> {
                MonitoringAlert alert = alertMapper.selectOne(new LambdaQueryWrapper<MonitoringAlert>()
                        .eq(MonitoringAlert::getId, command.sourceId()).eq(MonitoringAlert::getTenantId, tenantId));
                if (alert == null) throw new BusinessException("告警不存在或不属于当前租户");
                yield new SourceContext(alert.getId(), alert.getAlertNo(), "告警处置：" + alert.getTitle());
            }
            case INCIDENT -> {
                Incident incident = incidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                        .eq(Incident::getId, command.sourceId()).eq(Incident::getTenantId, tenantId));
                if (incident == null) throw new BusinessException("事件不存在或不属于当前租户");
                yield new SourceContext(incident.getId(), incident.getIncidentNo(), "事件处置：" + incident.getTitle());
            }
            case TICKET -> {
                Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getId, command.sourceId()).eq(Ticket::getTenantId, tenantId));
                if (ticket == null) throw new BusinessException("工单不存在或不属于当前租户");
                yield new SourceContext(ticket.getId(), ticket.getTicketNo(), "工单处置：" + ticket.getTitle());
            }
            case MANUAL -> throw new BusinessException("不支持的处置来源");
        };
    }

    private void ensureTargetCompatible(RunbookTargetType targetType, RunbookExecutionSource sourceType) {
        if (targetType == RunbookTargetType.GENERAL) return;
        if (sourceType == RunbookExecutionSource.MANUAL || !targetType.name().equals(sourceType.name())) {
            throw new BusinessException("该运行手册仅适用于 " + targetType + "，请从对应对象发起处置");
        }
    }

    private void ensureAlertCompatible(Runbook runbook) {
        if (runbook.getTargetType() != RunbookTargetType.ALERT && runbook.getTargetType() != RunbookTargetType.GENERAL) {
            throw new BusinessException("只有通用或告警类型运行手册可以配置告警推荐规则");
        }
    }

    private void validateAlertMatcher(AlertMatcherCommand command, Long tenantId) {
        if (command == null) throw new BusinessException("告警推荐规则不能为空");
        requiredText(command.name(), "推荐规则名称", 120);
        optionalText(command.titleKeyword(), "告警关键词", 160, null);
        if (command.alertSourceId() == null && command.minimumSeverity() == null
                && (command.titleKeyword() == null || command.titleKeyword().trim().isEmpty())) {
            throw new BusinessException("推荐规则至少需要配置告警源、最低严重度或标题关键词之一");
        }
        if (command.alertSourceId() != null) {
            AlertSource source = alertSourceMapper.selectOne(new LambdaQueryWrapper<AlertSource>()
                    .eq(AlertSource::getId, command.alertSourceId()).eq(AlertSource::getTenantId, tenantId));
            if (source == null) throw new BusinessException("告警源不存在或不属于当前租户");
        }
    }

    private void applyAlertMatcher(RunbookAlertMatcher target, AlertMatcherCommand command) {
        target.setName(requiredText(command.name(), "推荐规则名称", 120));
        target.setAlertSourceId(command.alertSourceId());
        target.setMinimumSeverity(command.minimumSeverity());
        target.setTitleKeyword(optionalText(command.titleKeyword(), "告警关键词", 160, null));
        target.setSortOrder(normalizeOrder(command.sortOrder()));
        target.setEnabled(command.enabled() == null || command.enabled());
    }

    private boolean matches(RunbookAlertMatcher matcher, MonitoringAlert alert) {
        if (matcher.getAlertSourceId() != null && !matcher.getAlertSourceId().equals(alert.getSourceId())) return false;
        if (matcher.getMinimumSeverity() != null && (alert.getSeverity() == null
                || !alert.getSeverity().atLeast(matcher.getMinimumSeverity()))) return false;
        if (matcher.getTitleKeyword() == null || matcher.getTitleKeyword().isBlank()) return true;
        String keyword = matcher.getTitleKeyword().trim().toLowerCase(Locale.ROOT);
        String haystack = String.join(" ", safeText(alert.getTitle()), safeText(alert.getDescription()), safeText(alert.getFingerprint()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(keyword);
    }

    private List<String> recommendationReasons(RunbookAlertMatcher matcher, MonitoringAlert alert) {
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        if (matcher.getAlertSourceId() != null) reasons.add("匹配告警源 #" + alert.getSourceId());
        if (matcher.getMinimumSeverity() != null) reasons.add("严重度 ≥ " + matcher.getMinimumSeverity());
        if (matcher.getTitleKeyword() != null && !matcher.getTitleKeyword().isBlank()) {
            reasons.add("命中关键词“" + matcher.getTitleKeyword().trim() + "”");
        }
        return reasons;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private List<RunbookAlertMatcher> alertMatchers(Long tenantId) {
        return alertMatcherMapper.selectList(new LambdaQueryWrapper<RunbookAlertMatcher>()
                .eq(RunbookAlertMatcher::getTenantId, tenantId)
                .orderByDesc(RunbookAlertMatcher::getEnabled)
                .orderByAsc(RunbookAlertMatcher::getSortOrder)
                .orderByAsc(RunbookAlertMatcher::getId)
                .last("LIMIT 240"));
    }

    private List<AlertSource> alertSources(Long tenantId) {
        return alertSourceMapper.selectList(new LambdaQueryWrapper<AlertSource>()
                .eq(AlertSource::getTenantId, tenantId)
                .orderByDesc(AlertSource::getEnabled)
                .orderByAsc(AlertSource::getName)
                .last("LIMIT 160"));
    }

    private List<RunbookStep> templateSteps(Long runbookId, Long tenantId) {
        return runbookStepMapper.selectList(new LambdaQueryWrapper<RunbookStep>()
                .eq(RunbookStep::getTenantId, tenantId).eq(RunbookStep::getRunbookId, runbookId)
                .orderByAsc(RunbookStep::getSortOrder).orderByAsc(RunbookStep::getId));
    }

    private List<RunbookExecutionStep> executionSteps(Long executionId, Long tenantId) {
        return executionStepMapper.selectList(new LambdaQueryWrapper<RunbookExecutionStep>()
                .eq(RunbookExecutionStep::getTenantId, tenantId).eq(RunbookExecutionStep::getExecutionId, executionId)
                .orderByAsc(RunbookExecutionStep::getSortOrder).orderByAsc(RunbookExecutionStep::getId));
    }

    private Runbook getRunbook(Long id, Long tenantId) {
        Runbook runbook = runbookMapper.selectOne(new LambdaQueryWrapper<Runbook>()
                .eq(Runbook::getId, id).eq(Runbook::getTenantId, tenantId));
        if (runbook == null) throw new BusinessException("运行手册不存在: " + id);
        return runbook;
    }

    private Runbook getOptionalRunbook(Long id, Long tenantId) {
        return runbookMapper.selectOne(new LambdaQueryWrapper<Runbook>()
                .eq(Runbook::getId, id).eq(Runbook::getTenantId, tenantId));
    }

    private RunbookAlertMatcher getAlertMatcher(Long id, Long tenantId) {
        RunbookAlertMatcher matcher = alertMatcherMapper.selectOne(new LambdaQueryWrapper<RunbookAlertMatcher>()
                .eq(RunbookAlertMatcher::getId, id).eq(RunbookAlertMatcher::getTenantId, tenantId));
        if (matcher == null) throw new BusinessException("告警推荐规则不存在: " + id);
        return matcher;
    }

    private Runbook lockRunbook(Long id, Long tenantId) {
        Runbook runbook = runbookMapper.selectForUpdate(id, tenantId);
        if (runbook == null) throw new BusinessException("运行手册不存在: " + id);
        return runbook;
    }

    private RunbookStep getTemplateStep(Long id, Long tenantId) {
        RunbookStep step = runbookStepMapper.selectOne(new LambdaQueryWrapper<RunbookStep>()
                .eq(RunbookStep::getId, id).eq(RunbookStep::getTenantId, tenantId));
        if (step == null) throw new BusinessException("运行手册步骤不存在: " + id);
        return step;
    }

    private RunbookExecution getExecution(Long id, Long tenantId) {
        RunbookExecution execution = executionMapper.selectOne(new LambdaQueryWrapper<RunbookExecution>()
                .eq(RunbookExecution::getId, id).eq(RunbookExecution::getTenantId, tenantId));
        if (execution == null) throw new BusinessException("运行手册执行记录不存在: " + id);
        return execution;
    }

    private RunbookExecution lockExecution(Long id, Long tenantId) {
        RunbookExecution execution = executionMapper.selectForUpdate(id, tenantId);
        if (execution == null) throw new BusinessException("运行手册执行记录不存在: " + id);
        return execution;
    }

    private RunbookExecutionStep getExecutionStep(Long id, Long tenantId) {
        RunbookExecutionStep step = executionStepMapper.selectOne(new LambdaQueryWrapper<RunbookExecutionStep>()
                .eq(RunbookExecutionStep::getId, id).eq(RunbookExecutionStep::getTenantId, tenantId));
        if (step == null) throw new BusinessException("运行手册执行步骤不存在: " + id);
        return step;
    }

    private void ensureExecutionOpen(RunbookExecution execution) {
        if (execution.getStatus() != RunbookExecutionStatus.IN_PROGRESS) {
            throw new BusinessException("当前处置已结束，不能继续修改步骤");
        }
    }

    private void ensureSlugAvailable(String slug, Long tenantId, Long excludeId) {
        Runbook existing = runbookMapper.selectOne(new LambdaQueryWrapper<Runbook>()
                .eq(Runbook::getTenantId, tenantId).eq(Runbook::getSlug, slug));
        if (existing != null && !existing.getId().equals(excludeId)) throw new BusinessException("该手册标识已存在，请换一个");
    }

    private void bumpVersion(Runbook runbook, Long actorId, LocalDateTime now) {
        runbook.setVersionNo(runbook.getVersionNo() + 1);
        runbook.setUpdatedBy(actorId);
        runbook.setUpdatedAt(now);
        runbookMapper.updateById(runbook);
    }

    private int normalizeOrder(Integer value) {
        if (value == null || value < 1) return 100;
        return Math.min(value, 100000);
    }

    private String normalizeSlug(String value) {
        String slug = requiredText(value, "手册标识", 140).toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessException("手册标识仅支持小写字母、数字及单个连字符");
        }
        return slug;
    }

    private String requiredText(String value, String field, int max) {
        if (value == null || value.trim().isEmpty()) throw new BusinessException(field + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > max) throw new BusinessException(field + "不能超过 " + max + " 个字符");
        return normalized;
    }

    private String optionalText(String value, String field, int max, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return requiredText(value, field, max);
    }

    private Runbook copyRunbook(Runbook source) {
        Runbook copy = new Runbook();
        copy.setId(source.getId()); copy.setTenantId(source.getTenantId()); copy.setName(source.getName());
        copy.setSlug(source.getSlug()); copy.setDescription(source.getDescription()); copy.setCategory(source.getCategory());
        copy.setTargetType(source.getTargetType()); copy.setVersionNo(source.getVersionNo()); copy.setEnabled(source.getEnabled());
        return copy;
    }

    private RunbookStep copyTemplateStep(RunbookStep source) {
        RunbookStep copy = new RunbookStep();
        copy.setId(source.getId()); copy.setRunbookId(source.getRunbookId()); copy.setSortOrder(source.getSortOrder());
        copy.setTitle(source.getTitle()); copy.setInstruction(source.getInstruction()); copy.setVerificationHint(source.getVerificationHint());
        copy.setRequiredStep(source.getRequiredStep());
        return copy;
    }

    private RunbookExecution copyExecution(RunbookExecution source) {
        RunbookExecution copy = new RunbookExecution();
        copy.setId(source.getId()); copy.setExecutionNo(source.getExecutionNo()); copy.setStatus(source.getStatus());
        copy.setOutcomeNote(source.getOutcomeNote()); copy.setRunbookId(source.getRunbookId()); copy.setRunbookVersion(source.getRunbookVersion());
        return copy;
    }

    private RunbookExecutionStep copyExecutionStep(RunbookExecutionStep source) {
        RunbookExecutionStep copy = new RunbookExecutionStep();
        copy.setId(source.getId()); copy.setExecutionId(source.getExecutionId()); copy.setStatus(source.getStatus());
        copy.setEvidence(source.getEvidence()); copy.setCompletedBy(source.getCompletedBy()); copy.setCompletedAt(source.getCompletedAt());
        return copy;
    }

    private RunbookAlertMatcher copyAlertMatcher(RunbookAlertMatcher source) {
        RunbookAlertMatcher copy = new RunbookAlertMatcher();
        copy.setId(source.getId()); copy.setTenantId(source.getTenantId()); copy.setRunbookId(source.getRunbookId());
        copy.setName(source.getName()); copy.setAlertSourceId(source.getAlertSourceId());
        copy.setMinimumSeverity(source.getMinimumSeverity()); copy.setTitleKeyword(source.getTitleKeyword());
        copy.setSortOrder(source.getSortOrder()); copy.setEnabled(source.getEnabled());
        return copy;
    }

    public record RunbookCommand(String name, String slug, String description, String category,
                                 RunbookTargetType targetType, Boolean enabled, List<TemplateStepCommand> steps) {}
    public record TemplateStepCommand(Integer sortOrder, String title, String instruction, String verificationHint,
                                      Boolean requiredStep) {}
    public record AlertMatcherCommand(String name, Long alertSourceId, com.opsflow.alert.AlertSeverity minimumSeverity,
                                      String titleKeyword, Integer sortOrder, Boolean enabled) {}
    public record ExecutionStartCommand(Long runbookId, RunbookExecutionSource sourceType, Long sourceId, String title) {}
    public record ExecutionStepCommand(RunbookStepStatus status, String evidence) {}
    public record RunbookCenterView(List<Runbook> runbooks, List<RunbookExecution> executions,
                                    List<RunbookAlertMatcher> alertMatchers, List<AlertSource> alertSources) {}
    public record RunbookDetail(Runbook runbook, List<RunbookStep> steps) {}
    public record ExecutionDetail(RunbookExecution execution, Runbook runbook, List<RunbookExecutionStep> steps) {}
    public record AlertRecommendation(Runbook runbook, RunbookAlertMatcher matcher, List<String> reasons) {}

    private record SourceContext(Long sourceId, String sourceRef, String defaultTitle) {}
    private record MatchedAlertRule(RunbookAlertMatcher matcher, Runbook runbook) {}
}
