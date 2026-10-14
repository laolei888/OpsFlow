package com.opsflow.slo;

import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.statuspage.StatusPageComponentMapper;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SloServiceTest {
    @Mock private SloDefinitionMapper definitionMapper;
    @Mock private SloMeasurementMapper measurementMapper;
    @Mock private SloBreachEventMapper breachMapper;
    @Mock private StatusPageComponentMapper statusComponentMapper;
    @Mock private MonitoringAlertMapper monitoringAlertMapper;
    @Mock private TicketService ticketService;
    @Mock private IncidentMapper incidentMapper;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private SloService service;

    @Test
    void createsCriticalBreachWhenErrorBudgetIsExhausted() {
        SloDefinition definition = definition();
        when(definitionMapper.selectOne(any())).thenReturn(definition);
        when(measurementMapper.insert(any(SloMeasurement.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SloMeasurement.class).setId(81L);
            return 1;
        });
        when(measurementMapper.selectList(any())).thenAnswer(invocation -> {
            SloMeasurement measurement = new SloMeasurement();
            measurement.setTotalRequests(1_000L);
            measurement.setGoodRequests(998L);
            return List.of(measurement);
        });
        when(breachMapper.selectOne(any())).thenReturn(null);
        when(breachMapper.insert(any(SloBreachEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SloBreachEvent.class).setId(91L);
            return 1;
        });

        SloService.MeasurementResult result = service.recordMeasurement(11L,
                new SloService.MeasurementCommand(1_000L, 998L, null, null, "过去一小时网关统计"), 3L, 7L);

        assertEquals(SloBreachLevel.CRITICAL, result.breach().getLevel());
        assertEquals(SloBreachStatus.OPEN, result.breach().getStatus());
        assertEquals(new BigDecimal("0.000"), result.snapshot().budgetRemainingPercent());
        verify(auditLogService).record(eq(3L), eq(7L), eq("SLO_BREACH"), eq(91L),
                eq("SLO_BREACH_CREATED"), eq(null), any(SloBreachEvent.class), any(String.class));
    }

    @Test
    void healthySampleDoesNotCreateFalseBudgetBreach() {
        SloDefinition definition = definition();
        when(definitionMapper.selectOne(any())).thenReturn(definition);
        when(measurementMapper.insert(any(SloMeasurement.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SloMeasurement.class).setId(82L);
            return 1;
        });
        when(measurementMapper.selectList(any())).thenAnswer(invocation -> {
            SloMeasurement measurement = new SloMeasurement();
            measurement.setTotalRequests(1_000L);
            measurement.setGoodRequests(1_000L);
            return List.of(measurement);
        });

        SloService.MeasurementResult result = service.recordMeasurement(11L,
                new SloService.MeasurementCommand(1_000L, 1_000L, null, null, null), 3L, 7L);

        assertNull(result.breach());
        assertEquals(new BigDecimal("100.000"), result.snapshot().budgetRemainingPercent());
        assertTrue(result.snapshot().dataAvailable());
        verify(breachMapper, never()).insert(any(SloBreachEvent.class));
    }

    private SloDefinition definition() {
        SloDefinition definition = new SloDefinition();
        definition.setId(11L);
        definition.setTenantId(3L);
        definition.setName("订单服务 30 天可用性");
        definition.setTargetPercent(new BigDecimal("99.900"));
        definition.setWindowDays(30);
        definition.setWarningBudgetPercent(new BigDecimal("40.000"));
        definition.setCriticalBudgetPercent(new BigDecimal("10.000"));
        definition.setEnabled(true);
        return definition;
    }
}
