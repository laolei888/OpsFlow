package com.opsflow.workbench;

import com.opsflow.alert.AlertSeverity;
import com.opsflow.alert.AlertStatus;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.catalog.ServiceRequest;
import com.opsflow.catalog.ServiceRequestMapper;
import com.opsflow.catalog.ServiceRequestStatus;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.change.ChangeRisk;
import com.opsflow.change.ChangeStatus;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.incident.IncidentSeverity;
import com.opsflow.incident.IncidentStatus;
import com.opsflow.postmortem.IncidentPostmortem;
import com.opsflow.postmortem.IncidentPostmortemMapper;
import com.opsflow.postmortem.PostmortemStatus;
import com.opsflow.slo.SloBreachEvent;
import com.opsflow.slo.SloBreachEventMapper;
import com.opsflow.slo.SloBreachLevel;
import com.opsflow.slo.SloBreachStatus;
import com.opsflow.statuspage.StatusPageIncident;
import com.opsflow.statuspage.StatusPageIncidentImpact;
import com.opsflow.statuspage.StatusPageIncidentMapper;
import com.opsflow.statuspage.StatusPageIncidentStatus;
import com.opsflow.ticket.TicketPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationsWorkbenchServiceTest {
    @Mock private ServiceRequestMapper serviceRequestMapper;
    @Mock private ChangeRequestMapper changeRequestMapper;
    @Mock private MonitoringAlertMapper alertMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private SloBreachEventMapper breachMapper;
    @Mock private StatusPageIncidentMapper statusIncidentMapper;
    @Mock private IncidentPostmortemMapper postmortemMapper;
    @InjectMocks private OperationsWorkbenchService service;

    @Test
    void aggregatesAdminWorkAndSortsCriticalItemsFirst() {
        LocalDateTime now = LocalDateTime.now();
        when(serviceRequestMapper.selectList(any())).thenReturn(List.of(serviceRequest(now.minusHours(5))));
        when(changeRequestMapper.selectList(any())).thenReturn(List.of(change(now.minusHours(4))));
        when(alertMapper.selectList(any())).thenReturn(List.of(alert(now.minusHours(3))));
        when(incidentMapper.selectList(any())).thenReturn(List.of(incident(now.minusHours(2))));
        when(breachMapper.selectList(any())).thenReturn(List.of(breach(now.minusHours(1))));
        when(statusIncidentMapper.selectList(any())).thenReturn(List.of(notice(now)));
        when(postmortemMapper.selectList(any())).thenReturn(List.of(postmortem(now.minusMinutes(30))));

        OperationsWorkbenchView view = service.overview(9L, true, null, null, 100);

        assertThat(view.summary().total()).isEqualTo(7);
        assertThat(view.summary().approvals()).isEqualTo(3);
        assertThat(view.summary().postmortemReviews()).isEqualTo(1);
        assertThat(view.summary().critical()).isEqualTo(4);
        assertThat(view.summary().firingAlerts()).isEqualTo(1);
        assertThat(view.items()).extracting(OperationsWorkbenchView.WorkItem::priority)
                .startsWith("CRITICAL", "CRITICAL", "CRITICAL", "CRITICAL");
        assertThat(view.items()).extracting(OperationsWorkbenchView.WorkItem::type)
                .containsExactlyInAnyOrder("SERVICE_APPROVAL", "CHANGE_APPROVAL", "FIRING_ALERT",
                        "ACTIVE_INCIDENT", "SLO_BREACH", "STATUS_DRAFT", "POSTMORTEM_REVIEW");
    }

    @Test
    void hidesAdminOnlyQueuesAndSupportsServerSideFiltersForAgent() {
        LocalDateTime now = LocalDateTime.now();
        when(alertMapper.selectList(any())).thenReturn(List.of(alert(now.minusHours(3))));
        when(incidentMapper.selectList(any())).thenReturn(List.of(incident(now.minusHours(2))));
        when(breachMapper.selectList(any())).thenReturn(List.of(breach(now.minusHours(1))));

        OperationsWorkbenchView view = service.overview(9L, false, "FIRING_ALERT,SLO_BREACH", "CRITICAL", 1);

        assertThat(view.summary().total()).isEqualTo(3);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).type()).isEqualTo("FIRING_ALERT");
        verify(serviceRequestMapper, never()).selectList(any());
        verify(changeRequestMapper, never()).selectList(any());
        verify(statusIncidentMapper, never()).selectList(any());
        verify(postmortemMapper, never()).selectList(any());
    }

    private ServiceRequest serviceRequest(LocalDateTime createdAt) {
        ServiceRequest request = new ServiceRequest();
        request.setId(1L); request.setTenantId(9L); request.setRequestNo("SR-001");
        request.setSummary("申请生产数据库只读权限"); request.setDetail("用于排查订单异常");
        request.setPriority(TicketPriority.HIGH); request.setStatus(ServiceRequestStatus.PENDING_APPROVAL);
        request.setCreatedAt(createdAt); request.setUpdatedAt(createdAt);
        return request;
    }

    private ChangeRequest change(LocalDateTime createdAt) {
        ChangeRequest change = new ChangeRequest();
        change.setId(2L); change.setTenantId(9L); change.setChangeNo("CHG-001");
        change.setTitle("网关限流参数调整"); change.setDescription("降低突发流量影响");
        change.setRiskLevel(ChangeRisk.HIGH); change.setStatus(ChangeStatus.PENDING_APPROVAL);
        change.setCreatedAt(createdAt); change.setUpdatedAt(createdAt);
        return change;
    }

    private MonitoringAlert alert(LocalDateTime createdAt) {
        MonitoringAlert alert = new MonitoringAlert();
        alert.setId(3L); alert.setTenantId(9L); alert.setAlertNo("ALT-001");
        alert.setTitle("订单网关错误率升高"); alert.setDescription("5xx 比例超过阈值");
        alert.setSeverity(AlertSeverity.CRITICAL); alert.setStatus(AlertStatus.FIRING);
        alert.setFirstSeenAt(createdAt); alert.setUpdatedAt(createdAt);
        return alert;
    }

    private Incident incident(LocalDateTime createdAt) {
        Incident incident = new Incident();
        incident.setId(4L); incident.setTenantId(9L); incident.setIncidentNo("INC-001");
        incident.setTitle("订单服务大面积超时"); incident.setSummary("用户无法提交订单");
        incident.setSeverity(IncidentSeverity.SEV1); incident.setStatus(IncidentStatus.INVESTIGATING);
        incident.setStartedAt(createdAt); incident.setUpdatedAt(createdAt);
        return incident;
    }

    private SloBreachEvent breach(LocalDateTime createdAt) {
        SloBreachEvent breach = new SloBreachEvent();
        breach.setId(5L); breach.setTenantId(9L); breach.setBreachNo("SLO-001");
        breach.setTitle("订单服务错误预算耗尽"); breach.setLevel(SloBreachLevel.CRITICAL);
        breach.setStatus(SloBreachStatus.OPEN); breach.setAvailabilityPercent(new BigDecimal("98.700"));
        breach.setBudgetRemainingPercent(BigDecimal.ZERO); breach.setCreatedAt(createdAt); breach.setUpdatedAt(createdAt);
        return breach;
    }

    private StatusPageIncident notice(LocalDateTime createdAt) {
        StatusPageIncident notice = new StatusPageIncident();
        notice.setId(6L); notice.setTenantId(9L); notice.setPublicNo("SP-001");
        notice.setTitle("订单服务异常公告"); notice.setSummary("部分用户下单失败");
        notice.setImpact(StatusPageIncidentImpact.CRITICAL); notice.setStatus(StatusPageIncidentStatus.INVESTIGATING);
        notice.setPublished(false); notice.setCreatedAt(createdAt); notice.setUpdatedAt(createdAt);
        return notice;
    }

    private IncidentPostmortem postmortem(LocalDateTime createdAt) {
        IncidentPostmortem review = new IncidentPostmortem();
        review.setId(7L); review.setTenantId(9L); review.setReviewNo("PIR-001");
        review.setTitle("订单服务重大事件复盘"); review.setExecutiveSummary("连接池耗尽导致订单服务异常");
        review.setStatus(PostmortemStatus.IN_REVIEW); review.setSubmittedAt(createdAt); review.setUpdatedAt(createdAt);
        return review;
    }
}
