package com.opsflow.runbook;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.alert.AlertSourceMapper;
import com.opsflow.alert.AlertSeverity;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.ticket.TicketMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunbookServiceTest {
    @Mock private RunbookMapper runbookMapper;
    @Mock private RunbookStepMapper runbookStepMapper;
    @Mock private RunbookAlertMatcherMapper alertMatcherMapper;
    @Mock private RunbookExecutionMapper executionMapper;
    @Mock private RunbookExecutionStepMapper executionStepMapper;
    @Mock private MonitoringAlertMapper alertMapper;
    @Mock private AlertSourceMapper alertSourceMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private TicketMapper ticketMapper;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private RunbookService service;

    @Test
    void requiredExecutionStepCannotBeSkipped() {
        RunbookExecutionStep step = executionStep(81L, true, RunbookStepStatus.PENDING);
        when(executionStepMapper.selectOne(any(Wrapper.class))).thenReturn(step);
        when(executionMapper.selectForUpdate(41L, 3L)).thenReturn(execution());

        assertThrows(BusinessException.class, () -> service.updateExecutionStep(81L,
                new RunbookService.ExecutionStepCommand(RunbookStepStatus.SKIPPED, "风险可忽略"), 3L, 9L));

        verify(executionStepMapper, never()).updateById(any(RunbookExecutionStep.class));
    }

    @Test
    void completionIsBlockedUntilEveryRequiredStepPasses() {
        when(executionMapper.selectForUpdate(41L, 3L)).thenReturn(execution());
        when(executionStepMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                executionStep(81L, true, RunbookStepStatus.PASSED),
                executionStep(82L, true, RunbookStepStatus.PENDING)));

        assertThrows(BusinessException.class, () -> service.complete(41L, "验证完成", 3L, 9L));

        verify(executionMapper, never()).updateById(any(RunbookExecution.class));
    }

    @Test
    void executionSnapshotsCurrentTemplateVersionAndSteps() {
        Runbook runbook = runbook(RunbookTargetType.GENERAL);
        when(runbookMapper.selectOne(any(Wrapper.class))).thenReturn(runbook);
        when(runbookStepMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                templateStep(11L, "确认影响"), templateStep(12L, "验证恢复")));
        when(executionMapper.insert(any(RunbookExecution.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, RunbookExecution.class).setId(41L);
            return 1;
        });

        RunbookExecution started = service.startExecution(new RunbookService.ExecutionStartCommand(1L,
                RunbookExecutionSource.MANUAL, null, null), 3L, 9L);

        assertEquals(RunbookExecutionStatus.IN_PROGRESS, started.getStatus());
        assertEquals(7, started.getRunbookVersion());
        assertEquals("MANUAL", started.getSourceRef());
        verify(executionStepMapper, times(2)).insert(any(RunbookExecutionStep.class));
    }

    @Test
    void alertRecommendationExplainsMatchedSourceSeverityAndKeyword() {
        MonitoringAlert alert = new MonitoringAlert();
        alert.setId(91L);
        alert.setTenantId(3L);
        alert.setSourceId(11L);
        alert.setSeverity(AlertSeverity.CRITICAL);
        alert.setTitle("订单 API 错误率过高");
        alert.setDescription("支付路径请求超时");
        RunbookAlertMatcher matcher = new RunbookAlertMatcher();
        matcher.setId(71L);
        matcher.setTenantId(3L);
        matcher.setRunbookId(1L);
        matcher.setName("订单生产告警");
        matcher.setAlertSourceId(11L);
        matcher.setMinimumSeverity(AlertSeverity.HIGH);
        matcher.setTitleKeyword("订单");
        matcher.setEnabled(true);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(alert);
        when(alertMatcherMapper.selectList(any(Wrapper.class))).thenReturn(List.of(matcher));
        when(runbookMapper.selectOne(any(Wrapper.class))).thenReturn(runbook(RunbookTargetType.ALERT));

        List<RunbookService.AlertRecommendation> recommendations = service.recommendationsForAlert(91L, 3L);

        assertEquals(1, recommendations.size());
        assertEquals(3, recommendations.get(0).reasons().size());
        assertEquals("订单生产告警", recommendations.get(0).matcher().getName());
    }

    private Runbook runbook(RunbookTargetType targetType) {
        Runbook runbook = new Runbook();
        runbook.setId(1L);
        runbook.setTenantId(3L);
        runbook.setName("支付超时排障");
        runbook.setSlug("payment-timeout");
        runbook.setTargetType(targetType);
        runbook.setVersionNo(7);
        runbook.setEnabled(true);
        return runbook;
    }

    private RunbookExecution execution() {
        RunbookExecution execution = new RunbookExecution();
        execution.setId(41L);
        execution.setTenantId(3L);
        execution.setExecutionNo("RBX-001");
        execution.setStatus(RunbookExecutionStatus.IN_PROGRESS);
        return execution;
    }

    private RunbookExecutionStep executionStep(Long id, boolean required, RunbookStepStatus status) {
        RunbookExecutionStep step = new RunbookExecutionStep();
        step.setId(id);
        step.setTenantId(3L);
        step.setExecutionId(41L);
        step.setRequiredStep(required);
        step.setStatus(status);
        return step;
    }

    private RunbookStep templateStep(Long id, String title) {
        RunbookStep step = new RunbookStep();
        step.setId(id);
        step.setTenantId(3L);
        step.setRunbookId(1L);
        step.setSortOrder(id.intValue());
        step.setTitle(title);
        step.setInstruction(title + " 的操作说明");
        step.setRequiredStep(true);
        return step;
    }
}
