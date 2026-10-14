package com.opsflow.search;

import com.opsflow.alert.AlertSeverity;
import com.opsflow.alert.AlertStatus;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.change.ChangeStatus;
import com.opsflow.cmdb.CmdbAsset;
import com.opsflow.cmdb.CmdbAssetMapper;
import com.opsflow.cmdb.AssetStatus;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.incident.IncidentStatus;
import com.opsflow.knowledge.KnowledgeArticle;
import com.opsflow.knowledge.KnowledgeMapper;
import com.opsflow.knowledge.KnowledgeStatus;
import com.opsflow.problem.Problem;
import com.opsflow.problem.ProblemMapper;
import com.opsflow.problem.ProblemStatus;
import com.opsflow.postmortem.IncidentPostmortem;
import com.opsflow.postmortem.IncidentPostmortemMapper;
import com.opsflow.postmortem.PostmortemStatus;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceTest {
    @Mock private TicketMapper ticketMapper;
    @Mock private KnowledgeMapper knowledgeMapper;
    @Mock private CmdbAssetMapper assetMapper;
    @Mock private ChangeRequestMapper changeMapper;
    @Mock private ProblemMapper problemMapper;
    @Mock private MonitoringAlertMapper alertMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private IncidentPostmortemMapper postmortemMapper;
    @InjectMocks private GlobalSearchService service;

    @Test
    void requesterSearchesOnlyOwnVisibleDomains() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket()));
        when(knowledgeMapper.selectList(any())).thenReturn(List.of(article()));

        GlobalSearchView view = service.search(3L, 7L, false, "VPN", 20);

        assertThat(view.total()).isEqualTo(2);
        assertThat(view.results()).extracting(GlobalSearchView.Result::type)
                .containsExactlyInAnyOrder("TICKET", "KNOWLEDGE");
        verify(assetMapper, never()).selectList(any());
        verify(changeMapper, never()).selectList(any());
        verify(problemMapper, never()).selectList(any());
        verify(alertMapper, never()).selectList(any());
        verify(incidentMapper, never()).selectList(any());
        verify(postmortemMapper, never()).selectList(any());
    }

    @Test
    void staffSearchesOperationalDomainsAndRanksExactReferenceFirst() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket()));
        when(knowledgeMapper.selectList(any())).thenReturn(List.of(article()));
        when(assetMapper.selectList(any())).thenReturn(List.of(asset()));
        when(changeMapper.selectList(any())).thenReturn(List.of(change()));
        when(problemMapper.selectList(any())).thenReturn(List.of(problem()));
        when(alertMapper.selectList(any())).thenReturn(List.of(alert()));
        when(incidentMapper.selectList(any())).thenReturn(List.of(incident()));
        when(postmortemMapper.selectList(any())).thenReturn(List.of(postmortem()));

        GlobalSearchView view = service.search(3L, 7L, true, "ALT-001", 50);

        assertThat(view.total()).isEqualTo(8);
        assertThat(view.results().get(0).type()).isEqualTo("ALERT");
        assertThat(view.results().get(0).referenceNo()).isEqualTo("ALT-001");
        assertThat(view.groups()).hasSize(8);
    }

    @Test
    void rejectsTooShortQuery() {
        assertThatThrownBy(() -> service.search(3L, 7L, true, "a", 30))
                .hasMessageContaining("至少需要 2 个字符");
    }

    private Ticket ticket() {
        Ticket value = new Ticket();
        value.setId(1L); value.setTicketNo("OPF-001"); value.setTitle("VPN 无法连接");
        value.setDescription("客户端连接超时"); value.setStatus(TicketStatus.OPEN); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private KnowledgeArticle article() {
        KnowledgeArticle value = new KnowledgeArticle();
        value.setId(2L); value.setSlug("vpn-guide"); value.setTitle("VPN 排障指南");
        value.setContent("检查账号和客户端配置"); value.setStatus(KnowledgeStatus.PUBLISHED); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private CmdbAsset asset() {
        CmdbAsset value = new CmdbAsset();
        value.setId(3L); value.setAssetNo("CI-001"); value.setName("VPN 网关");
        value.setDescription("远程接入服务"); value.setStatus(AssetStatus.ACTIVE); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private ChangeRequest change() {
        ChangeRequest value = new ChangeRequest();
        value.setId(4L); value.setChangeNo("CHG-001"); value.setTitle("VPN 网关升级");
        value.setDescription("修复连接问题"); value.setStatus(ChangeStatus.PENDING_APPROVAL); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private Problem problem() {
        Problem value = new Problem();
        value.setId(5L); value.setProblemNo("PRB-001"); value.setTitle("VPN 频繁断开");
        value.setDescription("分析链路稳定性"); value.setStatus(ProblemStatus.INVESTIGATING); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private MonitoringAlert alert() {
        MonitoringAlert value = new MonitoringAlert();
        value.setId(6L); value.setAlertNo("ALT-001"); value.setTitle("VPN 隧道异常");
        value.setDescription("ALT-001 多次触发"); value.setSeverity(AlertSeverity.HIGH);
        value.setStatus(AlertStatus.FIRING); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private Incident incident() {
        Incident value = new Incident();
        value.setId(7L); value.setIncidentNo("INC-001"); value.setTitle("VPN 服务中断");
        value.setSummary("远程办公用户无法接入"); value.setStatus(IncidentStatus.INVESTIGATING); value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private IncidentPostmortem postmortem() {
        IncidentPostmortem value = new IncidentPostmortem();
        value.setId(8L); value.setReviewNo("PIR-001"); value.setTitle("VPN 服务事件复盘");
        value.setRootCause("VPN 网关连接数达到上限"); value.setStatus(PostmortemStatus.IN_REVIEW);
        value.setUpdatedAt(LocalDateTime.now());
        return value;
    }
}
