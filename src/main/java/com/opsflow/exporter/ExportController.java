package com.opsflow.exporter;

import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/exports")
@RequiredArgsConstructor
public class ExportController {
    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final ExportService exportService;

    @GetMapping(value = "/tickets.csv", produces = "text/csv")
    public ResponseEntity<byte[]> tickets(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String ticketIds,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (ticketIds == null || ticketIds.isBlank()) {
            throw new BusinessException("请选择至少一张工单再导出");
        }
        boolean staff = principal.hasAnyRole("AGENT", "ADMIN");
        byte[] content = exportService.ticketsCsv(status, priority, keyword, overdue,
                mine && staff ? principal.id() : null,
                staff ? null : principal.id(), principal.tenantId(), principal.id(), fields, ticketIds);
        return csvResponse(content, "opsflow-tickets.csv");
    }

    @GetMapping(value = "/dashboard-report.csv", produces = "text/csv")
    public ResponseEntity<byte[]> dashboardReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以导出运营报表");
        }
        byte[] content = exportService.dashboardReportCsv(principal.tenantId(), from, to, principal.id());
        return csvResponse(content, "opsflow-dashboard-report.csv");
    }
    @GetMapping(value = "/audit-logs.csv", produces = "text/csv")
    public ResponseEntity<byte[]> auditLogs(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actions,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "200") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以导出审计日志");
        }
        byte[] content = exportService.auditLogsCsv(principal.tenantId(), actorId, action, actions,
                from, to, limit, principal.id());
        return csvResponse(content, "opsflow-audit-logs.csv");
    }

    private ResponseEntity<byte[]> csvResponse(byte[] content, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CSV_MEDIA_TYPE);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
