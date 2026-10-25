package com.opsflow.chaos;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChaosExperimentServiceTest {
    @Mock private ChaosExperimentMapper experimentMapper;
    @Mock private ChaosExperimentCheckMapper checkMapper;
    @Mock private AuditLogService auditLogService;

    @Test
    void rejectsProductionEnvironmentBeforeCreatingExperiment() {
        ChaosExperimentService.CreateCommand command = new ChaosExperimentService.CreateCommand(
                "Redis outage", ChaosTarget.REDIS, "production-primary",
                "Ticket reads remain available", "Restore Redis and validate cache", 10);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().create(command, 3L, 9L));

        assertThat(exception.getMessage()).contains("禁止使用生产环境");
        verify(experimentMapper, never()).insert(any(ChaosExperiment.class));
    }

    @Test
    void createsSixPhaseGatesAndKeepsInfrastructureManual() {
        when(experimentMapper.insert(any(ChaosExperiment.class))).thenAnswer(invocation -> {
            ChaosExperiment value = invocation.getArgument(0);
            value.setId(41L);
            return 1;
        });
        when(experimentMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> created());
        when(checkMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        ChaosExperimentService.ExperimentDetail result = service().create(
                new ChaosExperimentService.CreateCommand("Broker outage", ChaosTarget.RABBITMQ,
                        "chaos-lab", "Outbox retains every event", "Restore broker and drain backlog", 15),
                3L, 9L);

        ArgumentCaptor<ChaosExperimentCheck> checks = ArgumentCaptor.forClass(ChaosExperimentCheck.class);
        verify(checkMapper, org.mockito.Mockito.times(6)).insert(checks.capture());
        assertThat(checks.getAllValues()).extracting(ChaosExperimentCheck::getCheckKey)
                .containsExactly("BASELINE_HEALTH", "FAULT_INJECTION_EVIDENCE", "FALLBACK_BEHAVIOR",
                        "ALERT_OBSERVED", "RECOVERY_HEALTH", "DATA_INTEGRITY");
        assertThat(result.playbook().injectionGuide()).contains("隔离环境");
    }

    @Test
    void blocksFaultInjectionUntilBaselineEvidencePasses() {
        ChaosExperiment experiment = created();
        experiment.setStatus(ChaosExperimentStatus.BASELINE);
        when(experimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment);
        when(checkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                check(1L, ChaosCheckPhase.BASELINE, ChaosCheckStatus.PENDING)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().confirmFaultInjected(41L, 3L, 9L));

        assertThat(exception.getMessage()).contains("全部必需检查项");
        verify(experimentMapper, never()).updateById(any(ChaosExperiment.class));
    }

    @Test
    void baselineFailurePreventsUnsafeFaultInjection() {
        ChaosExperiment experiment = created();
        experiment.setStatus(ChaosExperimentStatus.BASELINE);
        when(experimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment);
        when(checkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                check(1L, ChaosCheckPhase.BASELINE, ChaosCheckStatus.FAILED)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().confirmFaultInjected(41L, 3L, 9L));

        assertThat(exception.getMessage()).contains("禁止注入故障");
    }

    @Test
    void futurePhaseEvidenceCannotBeRecordedEarly() {
        ChaosExperiment experiment = created();
        experiment.setStatus(ChaosExperimentStatus.BASELINE);
        when(experimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment);
        when(checkMapper.selectOne(any(Wrapper.class))).thenReturn(
                check(6L, ChaosCheckPhase.RECOVERY, ChaosCheckStatus.PENDING));

        BusinessException exception = assertThrows(BusinessException.class, () -> service().updateCheck(
                41L, 6L, new ChaosExperimentService.CheckCommand(ChaosCheckStatus.PASSED,
                        "healthy", "health endpoint returned UP"), 3L, 9L));

        assertThat(exception.getMessage()).contains("当前实验阶段");
        verify(checkMapper, never()).updateById(any(ChaosExperimentCheck.class));
    }

    @Test
    void completedPhaseEvidenceIsFrozenAfterTransition() {
        ChaosExperiment experiment = created();
        experiment.setStatus(ChaosExperimentStatus.FAULT_INJECTED);
        when(experimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment);
        when(checkMapper.selectOne(any(Wrapper.class))).thenReturn(
                check(1L, ChaosCheckPhase.BASELINE, ChaosCheckStatus.PASSED));

        BusinessException exception = assertThrows(BusinessException.class, () -> service().updateCheck(
                41L, 1L, new ChaosExperimentService.CheckCommand(ChaosCheckStatus.WARNING,
                        "late edit", "attempted historical evidence rewrite"), 3L, 9L));

        assertThat(exception.getMessage()).contains("当前实验阶段");
        verify(checkMapper, never()).updateById(any(ChaosExperimentCheck.class));
    }

    @Test
    void successfulRecoveryWithinTargetPassesExperiment() {
        ChaosExperiment experiment = created();
        experiment.setStatus(ChaosExperimentStatus.RECOVERING);
        experiment.setFaultStartedAt(LocalDateTime.now().minusSeconds(30));
        when(experimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment);
        List<ChaosExperimentCheck> passed = List.of(
                check(1L, ChaosCheckPhase.BASELINE, ChaosCheckStatus.PASSED),
                check(2L, ChaosCheckPhase.FAULT, ChaosCheckStatus.PASSED),
                check(3L, ChaosCheckPhase.RECOVERY, ChaosCheckStatus.PASSED),
                check(4L, ChaosCheckPhase.RECOVERY, ChaosCheckStatus.PASSED));
        when(checkMapper.selectList(any(Wrapper.class))).thenReturn(passed);
        when(experimentMapper.updateById(any(ChaosExperiment.class))).thenReturn(1);

        ChaosExperimentService.ExperimentDetail result = service().complete(41L,
                new ChaosExperimentService.CompleteCommand("Fallback and recovery behavior matched the hypothesis"),
                3L, 9L);

        assertThat(result.experiment().getStatus()).isEqualTo(ChaosExperimentStatus.PASSED);
        assertThat(result.experiment().getAchievedRecoverySeconds()).isBetween(1L, 60L);
    }

    private ChaosExperimentService service() {
        return new ChaosExperimentService(experimentMapper, checkMapper, auditLogService);
    }

    private ChaosExperiment created() {
        ChaosExperiment experiment = new ChaosExperiment();
        experiment.setId(41L);
        experiment.setTenantId(3L);
        experiment.setExperimentNo("CH-TEST");
        experiment.setName("Broker outage");
        experiment.setTarget(ChaosTarget.RABBITMQ);
        experiment.setEnvironment("chaos-lab");
        experiment.setStatus(ChaosExperimentStatus.PLANNED);
        experiment.setVersion(0);
        experiment.setTargetRecoveryMinutes(10);
        experiment.setCreatedAt(LocalDateTime.now());
        return experiment;
    }

    private ChaosExperimentCheck check(Long id, ChaosCheckPhase phase, ChaosCheckStatus status) {
        ChaosExperimentCheck check = new ChaosExperimentCheck();
        check.setId(id);
        check.setTenantId(3L);
        check.setExperimentId(41L);
        check.setPhase(phase);
        check.setCheckKey("CHECK-" + id);
        check.setStatus(status);
        check.setRequiredCheck(true);
        return check;
    }
}
