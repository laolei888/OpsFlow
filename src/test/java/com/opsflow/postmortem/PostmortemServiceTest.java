package com.opsflow.postmortem;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.incident.IncidentSeverity;
import com.opsflow.incident.IncidentStatus;
import com.opsflow.messaging.AsyncNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostmortemServiceTest {
    @Mock private IncidentPostmortemMapper postmortemMapper;
    @Mock private IncidentPostmortemActionMapper actionMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private AuthService authService;
    @Mock private AsyncNotificationPublisher notificationPublisher;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private PostmortemService service;

    @Test
    void createsTenantScopedDraftForIncident() {
        when(incidentMapper.selectOne(any(Wrapper.class))).thenReturn(incident(IncidentStatus.INVESTIGATING));
        when(authService.isStaffMember(7L, 3L)).thenReturn(true);
        when(authService.isStaffMember(9L, 3L)).thenReturn(true);
        when(postmortemMapper.insert(any(IncidentPostmortem.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, IncidentPostmortem.class).setId(81L);
            return 1;
        });

        IncidentPostmortem result = service.create(new PostmortemService.CreateCommand(
                61L, null, 7L, 9L, LocalDateTime.now().plusDays(7)), 7L, 3L);

        assertEquals(PostmortemStatus.DRAFT, result.getStatus());
        assertEquals(61L, result.getIncidentId());
        assertEquals(9L, result.getReviewerId());
        assertNotNull(result.getReviewNo());
        verify(auditLogService).record(eq(3L), eq(7L), eq("POSTMORTEM"), eq(81L),
                eq("POSTMORTEM_CREATED"), eq(null), eq(result), contains("INC-TEST61"));
    }

    @Test
    void submitRequiresResolvedIncident() {
        IncidentPostmortem postmortem = completePostmortem(PostmortemStatus.DRAFT);
        when(postmortemMapper.selectForUpdate(81L, 3L)).thenReturn(postmortem);
        when(incidentMapper.selectOne(any(Wrapper.class))).thenReturn(incident(IncidentStatus.MONITORING));
        when(actionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(action(PostmortemActionStatus.OPEN)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submit(81L, 7L, 3L));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("重大事件需先解决"));
        verify(postmortemMapper, never()).updateById(any(IncidentPostmortem.class));
    }

    @Test
    void submitMovesCompleteReviewToQueueAndNotifiesReviewer() {
        IncidentPostmortem postmortem = completePostmortem(PostmortemStatus.DRAFT);
        when(postmortemMapper.selectForUpdate(81L, 3L)).thenReturn(postmortem);
        when(incidentMapper.selectOne(any(Wrapper.class))).thenReturn(incident(IncidentStatus.RESOLVED));
        when(actionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(action(PostmortemActionStatus.OPEN)));

        IncidentPostmortem result = service.submit(81L, 7L, 3L);

        assertEquals(PostmortemStatus.IN_REVIEW, result.getStatus());
        assertNotNull(result.getSubmittedAt());
        verify(notificationPublisher).enqueue(eq(3L), eq(9L), eq(null),
                eq("POSTMORTEM_REVIEW_REQUIRED"), eq("重大事件复盘待评审"), contains("已提交"),
                contains("POSTMORTEM_REVIEW_REQUIRED:81"), eq("POSTMORTEM"), eq(81L));
        verify(auditLogService).record(eq(3L), eq(7L), eq("POSTMORTEM"), eq(81L),
                eq("POSTMORTEM_SUBMITTED"), any(), eq(result), contains("提交"));
    }

    @Test
    void administratorCanRejectReviewBackToDraft() {
        IncidentPostmortem postmortem = completePostmortem(PostmortemStatus.IN_REVIEW);
        postmortem.setSubmittedAt(LocalDateTime.now().minusHours(2));
        when(postmortemMapper.selectForUpdate(81L, 3L)).thenReturn(postmortem);

        IncidentPostmortem result = service.review(81L, false, "根因证据不足", 11L, 3L);

        assertEquals(PostmortemStatus.DRAFT, result.getStatus());
        assertEquals("根因证据不足", result.getRejectedReason());
        verify(notificationPublisher).enqueue(eq(3L), eq(7L), eq(null), eq("POSTMORTEM_REJECTED"),
                eq("重大事件复盘需修改"), contains("根因证据不足"), contains("POSTMORTEM_REJECTED:81"),
                eq("POSTMORTEM"), eq(81L));
    }

    @Test
    void completingCorrectiveActionRequiresEvidence() {
        IncidentPostmortem postmortem = completePostmortem(PostmortemStatus.APPROVED);
        IncidentPostmortemAction action = action(PostmortemActionStatus.IN_PROGRESS);
        when(actionMapper.selectForUpdate(91L, 3L)).thenReturn(action);
        when(postmortemMapper.selectForUpdate(81L, 3L)).thenReturn(postmortem);

        assertThrows(BusinessException.class, () -> service.updateAction(91L,
                new PostmortemService.ActionUpdateCommand(null, null, null, null, null,
                        PostmortemActionStatus.DONE, ""), 7L, 3L));

        verify(actionMapper, never()).updateById(any(IncidentPostmortemAction.class));
    }

    private Incident incident(IncidentStatus status) {
        Incident incident = new Incident();
        incident.setId(61L);
        incident.setTenantId(3L);
        incident.setIncidentNo("INC-TEST61");
        incident.setTitle("订单服务大面积超时");
        incident.setSummary("生产环境订单服务超时");
        incident.setSeverity(IncidentSeverity.SEV1);
        incident.setStatus(status);
        return incident;
    }

    private IncidentPostmortem completePostmortem(PostmortemStatus status) {
        IncidentPostmortem postmortem = new IncidentPostmortem();
        postmortem.setId(81L);
        postmortem.setTenantId(3L);
        postmortem.setIncidentId(61L);
        postmortem.setReviewNo("PIR-TEST0081");
        postmortem.setTitle("订单服务重大事件复盘");
        postmortem.setStatus(status);
        postmortem.setFacilitatorId(7L);
        postmortem.setReviewerId(9L);
        postmortem.setExecutiveSummary("数据库连接池耗尽导致订单接口不可用。");
        postmortem.setCustomerImpact("部分客户无法创建订单。");
        postmortem.setRootCause("慢查询长期占用数据库连接。");
        postmortem.setResolutionSummary("扩容连接池并终止异常查询。");
        postmortem.setLessonsLearned("容量基线与慢查询告警不足。");
        postmortem.setPreventionPlan("补充容量压测、慢查询告警与自动降级。");
        postmortem.setCreatedBy(7L);
        postmortem.setCreatedAt(LocalDateTime.now().minusDays(1));
        postmortem.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return postmortem;
    }

    private IncidentPostmortemAction action(PostmortemActionStatus status) {
        IncidentPostmortemAction action = new IncidentPostmortemAction();
        action.setId(91L);
        action.setTenantId(3L);
        action.setPostmortemId(81L);
        action.setTitle("补充连接池容量告警");
        action.setOwnerId(7L);
        action.setPriority(PostmortemActionPriority.HIGH);
        action.setStatus(status);
        action.setDueAt(LocalDateTime.now().plusDays(5));
        return action;
    }
}
