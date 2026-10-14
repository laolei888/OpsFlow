package com.opsflow.exporter;

import com.opsflow.audit.AuditLog;
import com.opsflow.dashboard.DashboardReport;
import com.opsflow.dashboard.DashboardService;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketService;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {
    @Mock
    private TicketService ticketService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private DashboardService dashboardService;

    @InjectMocks
    private ExportService exportService;

    @Test
    void ticketCsvUsesBomAndEscapesSpreadsheetContent() {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("OPF-0001");
        ticket.setTitle("VPN, 无法 \"连接\"");
        ticket.setDescription("第一行\n第二行");
        ticket.setCategory("网络");
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setRequesterId(7L);
        ticket.setCreatedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
        when(ticketService.export(any(), any(), any(), eq(false), eq(null), eq(7L), eq(3L), eq(5000)))
                .thenReturn(List.of(ticket));

        String csv = new String(exportService.ticketsCsv(null, null, null, false,
                null, 7L, 3L, 7L));

        assertTrue(csv.startsWith("\uFEFF工单编号"));
        assertTrue(csv.contains("\"VPN, 无法 \"\"连接\"\"\""));
        assertTrue(csv.contains("\"第一行\n第二行\""));
        verify(auditLogService).record(eq(3L), eq(7L), eq("EXPORT"), eq(null),
                eq("EXPORT_GENERATED"), eq(null), eq(null), any());
    }

    @Test
    void dashboardReportCsvContainsSummaryAndBreakdowns() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 14);
        DashboardReport report = new DashboardReport(
                from, to, 12, 8, 4, 2, 18.5, 75, 8,
                List.of(new DashboardReport.AssigneeMetric(11L, 7, 5, 1, 16.2, 80)),
                List.of(new DashboardReport.CategoryMetric("网络", 6, 4, 2)));
        when(dashboardService.report(eq(3L), eq(from), eq(to))).thenReturn(report);

        String csv = new String(exportService.dashboardReportCsv(3L, from, to, 7L));

        assertTrue(csv.startsWith("\uFEFF\"报表区间\""));
        assertTrue(csv.contains("\"周期新增\",\"12\""));
        assertTrue(csv.contains("\"平均解决时长（小时）\",\"18.5\""));
        assertTrue(csv.contains("\"处理人\",\"11\",\"7\",\"5\",\"1\",\"16.2\",\"80\""));
        assertTrue(csv.contains("\"分类\",\"网络\",\"6\",\"4\",\"2\""));
        verify(auditLogService).record(eq(3L), eq(7L), eq("EXPORT"), eq(null),
                eq("EXPORT_GENERATED"), eq(null), eq(null),
                org.mockito.ArgumentMatchers.contains("导出工单运营报表 CSV"));
    }
    @Test
    void auditCsvContainsFilteredAuditColumnsAndRecordsExport() {
        AuditLog log = new AuditLog();
        log.setId(10L);
        log.setActorId(7L);
        log.setResourceType("TICKET");
        log.setResourceId(2L);
        log.setAction("STATUS_CHANGED");
        log.setDetail("工单状态更新");
        log.setCreatedAt(LocalDateTime.of(2026, 9, 20, 11, 0));
        when(auditLogService.search(eq(3L), eq(7L), eq("STATUS_CHANGED"), any(), any(), eq(20)))
                .thenReturn(List.of(log));

        String csv = new String(exportService.auditLogsCsv(3L, 7L, "STATUS_CHANGED",
                LocalDateTime.now().minusDays(1), LocalDateTime.now(), 20, 1L));

        assertTrue(csv.contains("日志ID,操作者ID"));
        assertTrue(csv.contains("\"STATUS_CHANGED\""));
        assertTrue(csv.contains("\"工单状态更新\""));
        verify(auditLogService).record(eq(3L), eq(1L), eq("EXPORT"), eq(null),
                eq("EXPORT_GENERATED"), eq(null), eq(null), any());
    }

    @Test
    void ticketCsvCanExportOnlySelectedColumns() {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("OPF-0002");
        ticket.setTitle("只导出标题");
        ticket.setDescription("不应出现在本次导出");
        ticket.setPriority(TicketPriority.URGENT);
        when(ticketService.export(any(), any(), any(), eq(false), eq(null), eq(7L), eq(3L), eq(5000)))
                .thenReturn(List.of(ticket));

        String csv = new String(exportService.ticketsCsv(null, null, null, false,
                null, 7L, 3L, 7L, "ticketNo,title,priority"));

        assertTrue(csv.startsWith("\uFEFF工单编号,标题,优先级\n"));
        assertTrue(csv.contains("\"OPF-0002\",\"只导出标题\",\"URGENT\""));
        verify(auditLogService).record(eq(3L), eq(7L), eq("EXPORT"), eq(null),
                eq("EXPORT_GENERATED"), eq(null), eq(null), any());
    }

    @Test
    void ticketCsvRejectsUnsupportedSelectedColumn() {
        assertThrows(BusinessException.class, () -> exportService.ticketsCsv(null, null, null,
                false, null, 7L, 3L, 7L, "ticketNo,secret"));
    }

    @Test
    void auditCsvCanExportSelectedEventTypes() {
        AuditLog log = new AuditLog();
        log.setAction("STATUS_CHANGED");
        log.setDetail("状态更新");
        when(auditLogService.search(eq(3L), eq(7L),
                org.mockito.ArgumentMatchers.<String>anyCollection(),
                any(), any(), eq(20)))
                .thenReturn(List.of(log));

        String csv = new String(exportService.auditLogsCsv(3L, 7L, null,
                "CREATED,STATUS_CHANGED",
                LocalDateTime.now().minusDays(1), LocalDateTime.now(), 20, 1L));

        assertTrue(csv.contains("\"STATUS_CHANGED\""));
        verify(auditLogService).search(eq(3L), eq(7L),
                argThat((java.util.Collection<String> actions) ->
                        actions.size() == 2
                                && actions.contains("CREATED")
                                && actions.contains("STATUS_CHANGED")),
                any(), any(), eq(20));
    }

    @Test
    void ticketCsvCanExportOnlySelectedTicketIds() {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("OPF-SELECTED");
        ticket.setTitle("仅导出选中的工单");
        when(ticketService.exportByIds(eq(Set.of(12L, 15L)), any(), any(), any(),
                eq(false), eq(null), eq(7L), eq(3L), eq(5000)))
                .thenReturn(List.of(ticket));

        String csv = new String(exportService.ticketsCsv(null, null, null, false,
                null, 7L, 3L, 7L, "ticketNo,title", "12,15"));

        assertTrue(csv.contains("\"OPF-SELECTED\",\"仅导出选中的工单\""));
        verify(ticketService).exportByIds(eq(Set.of(12L, 15L)), any(), any(), any(),
                eq(false), eq(null), eq(7L), eq(3L), eq(5000));
    }

    @Test
    void ticketCsvRejectsInvalidSelectedTicketIds() {
        assertThrows(BusinessException.class, () -> exportService.ticketsCsv(null, null, null,
                false, null, 7L, 3L, 7L, "ticketNo", "abc"));
    }
}