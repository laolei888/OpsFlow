package com.opsflow.statuspage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.cmdb.CmdbAssetService;
import com.opsflow.incident.IncidentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusPageServiceTest {
    @Mock private StatusPageComponentMapper componentMapper;
    @Mock private StatusPageIncidentMapper incidentMapper;
    @Mock private StatusPageIncidentComponentMapper incidentComponentMapper;
    @Mock private StatusPageUpdateMapper updateMapper;
    @Mock private IncidentMapper internalIncidentMapper;
    @Mock private CmdbAssetService cmdbAssetService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private StatusPageService service;

    @Test
    void draftDoesNotChangeAComponentUntilAnAdminPublishesIt() {
        StatusPageComponent component = component(31L, "order-api");
        when(componentMapper.selectBatchIds(any())).thenReturn(List.of(component));
        when(incidentMapper.insert(any(StatusPageIncident.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, StatusPageIncident.class).setId(71L);
            return 1;
        });

        StatusPageService.IncidentDetail detail = service.createIncident(new StatusPageService.IncidentCommand(
                "订单服务响应缓慢", "部分用户可能遇到请求响应时间延长。", StatusPageIncidentImpact.MAJOR,
                List.of(31L), null), 3L, 7L);

        assertFalse(detail.incident().getPublished());
        assertEquals(StatusPageIncidentStatus.INVESTIGATING, detail.incident().getStatus());
        verify(componentMapper, never()).updateById(any(StatusPageComponent.class));
        verify(incidentComponentMapper).insert(any(StatusPageIncidentComponent.class));
        verify(updateMapper).insert(any(StatusPageUpdate.class));
    }

    @Test
    void publicSnapshotContainsOnlyPublicSafeFields() throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        StatusPageComponent component = component(31L, "order-api");
        component.setUpdatedAt(timestamp);
        StatusPageIncident incident = new StatusPageIncident();
        incident.setId(71L);
        incident.setTenantId(3L);
        incident.setPublicNo("SP-ABC123");
        incident.setSourceIncidentId(998L);
        incident.setTitle("订单服务响应缓慢");
        incident.setSummary("部分用户可能遇到请求响应时间延长。");
        incident.setImpact(StatusPageIncidentImpact.MAJOR);
        incident.setStatus(StatusPageIncidentStatus.INVESTIGATING);
        incident.setPublished(true);
        incident.setPublishedAt(timestamp);
        incident.setUpdatedAt(timestamp);
        StatusPageIncidentComponent link = new StatusPageIncidentComponent();
        link.setTenantId(3L);
        link.setStatusIncidentId(71L);
        link.setComponentId(31L);
        StatusPageUpdate update = new StatusPageUpdate();
        update.setStatus(StatusPageIncidentStatus.INVESTIGATING);
        update.setMessage("我们正在调查此问题。");
        update.setCreatedAt(timestamp);
        when(componentMapper.selectList(any())).thenReturn(List.of(component));
        when(incidentMapper.selectList(any())).thenReturn(List.of(incident));
        when(incidentComponentMapper.selectList(any())).thenReturn(List.of(link));
        when(updateMapper.selectList(any())).thenReturn(List.of(update));

        StatusPageService.PublicStatusPage snapshot = service.publicSnapshot(3L);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);

        assertEquals("SP-ABC123", snapshot.incidents().get(0).publicNo());
        assertEquals(List.of("order-api"), snapshot.incidents().get(0).componentSlugs());
        assertTrue(json.contains("订单服务响应缓慢"));
        assertFalse(json.contains("sourceIncidentId"));
        assertFalse(json.contains("tenantId"));
        assertFalse(json.contains("998"));
    }

    private StatusPageComponent component(Long id, String slug) {
        StatusPageComponent component = new StatusPageComponent();
        component.setId(id);
        component.setTenantId(3L);
        component.setSlug(slug);
        component.setName("订单服务");
        component.setDescription("为客户提供订单创建与查询能力。");
        component.setStatus(StatusPageComponentStatus.OPERATIONAL);
        component.setEnabled(true);
        component.setUpdatedAt(LocalDateTime.now());
        return component;
    }
}
