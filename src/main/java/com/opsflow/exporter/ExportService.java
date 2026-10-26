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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExportService {
    private static final String BOM = "\uFEFF";

    private static final List<TicketExportColumn> TICKET_COLUMNS = List.of(
            new TicketExportColumn("ticketNo", "工单编号", Ticket::getTicketNo),
            new TicketExportColumn("title", "标题", Ticket::getTitle),
            new TicketExportColumn("category", "分类", Ticket::getCategory),
            new TicketExportColumn("priority", "优先级", Ticket::getPriority),
            new TicketExportColumn("status", "状态", Ticket::getStatus),
            new TicketExportColumn("requesterId", "申请人ID", Ticket::getRequesterId),
            new TicketExportColumn("assigneeId", "处理人ID", Ticket::getAssigneeId),
            new TicketExportColumn("dueAt", "SLA截止", Ticket::getDueAt),
            new TicketExportColumn("createdAt", "创建时间", Ticket::getCreatedAt),
            new TicketExportColumn("updatedAt", "更新时间", Ticket::getUpdatedAt),
            new TicketExportColumn("description", "描述", Ticket::getDescription),
            new TicketExportColumn("impactScope", "影响范围", Ticket::getImpactScope),
            new TicketExportColumn("expectedOutcome", "期望结果", Ticket::getExpectedOutcome),
            new TicketExportColumn("attemptedActions", "已尝试措施", Ticket::getAttemptedActions),
            new TicketExportColumn("resolutionCode", "解决类型", Ticket::getResolutionCode),
            new TicketExportColumn("resolutionSummary", "解决摘要", Ticket::getResolutionSummary),
            new TicketExportColumn("verificationResult", "验证结果", Ticket::getVerificationResult),
            new TicketExportColumn("resolutionSubmittedAt", "方案提交时间", Ticket::getResolutionSubmittedAt),
            new TicketExportColumn("confirmedAt", "申请人确认时间", Ticket::getConfirmedAt)
    );

    private final TicketService ticketService;
    private final AuditLogService auditLogService;
    private final DashboardService dashboardService;

    public byte[] ticketsCsv(TicketStatus status, TicketPriority priority, String keyword,
                             boolean overdue, Long assigneeId, Long requesterId,
                             Long tenantId, Long actorId) {
        return ticketsCsv(status, priority, keyword, overdue, assigneeId, requesterId,
                tenantId, actorId, null, null);
    }

    public byte[] ticketsCsv(TicketStatus status, TicketPriority priority, String keyword,
                             boolean overdue, Long assigneeId, Long requesterId,
                             Long tenantId, Long actorId, String fields) {
        return ticketsCsv(status, priority, keyword, overdue, assigneeId, requesterId,
                tenantId, actorId, fields, null);
    }

    public byte[] ticketsCsv(TicketStatus status, TicketPriority priority, String keyword,
                             boolean overdue, Long assigneeId, Long requesterId,
                             Long tenantId, Long actorId, String fields, String ticketIds) {
        Set<Long> selectedIds = resolveTicketIds(ticketIds);
        if (ticketIds != null && selectedIds.isEmpty()) {
            throw new BusinessException("至少选择一张工单");
        }
        List<Ticket> tickets = selectedIds.isEmpty()
                ? ticketService.export(status, priority, keyword, overdue,
                assigneeId, requesterId, tenantId, 5000)
                : ticketService.exportByIds(selectedIds, status, priority, keyword, overdue,
                assigneeId, requesterId, tenantId, 5000);
        List<TicketExportColumn> columns = resolveTicketColumns(fields);
        StringBuilder csv = new StringBuilder(BOM);
        appendHeader(csv, columns);
        for (Ticket ticket : tickets) {
            appendRow(csv, columns.stream()
                    .map(column -> column.value().apply(ticket))
                    .toArray());
        }
        String selectionDetail = selectedIds.isEmpty()
                ? "按当前筛选条件"
                : "指定工单 " + selectedIds.size() + " 张";
        auditLogService.record(tenantId, actorId, "EXPORT", null, "EXPORT_GENERATED",
                null, null, "导出工单 CSV，共 " + tickets.size() + " 条，" + selectionDetail + "，字段："
                        + columns.stream().map(TicketExportColumn::label).collect(Collectors.joining("、")));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Set<Long> resolveTicketIds(String rawTicketIds) {
        if (rawTicketIds == null || rawTicketIds.isBlank()) return new LinkedHashSet<>();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String rawId : rawTicketIds.split(",")) {
            String value = rawId.trim();
            if (value.isBlank()) continue;
            try {
                long id = Long.parseLong(value);
                if (id <= 0) throw new NumberFormatException();
                result.add(id);
            } catch (NumberFormatException ex) {
                throw new BusinessException("工单 ID 格式不正确");
            }
        }
        if (result.size() > 200) throw new BusinessException("单次最多选择 200 张工单导出");
        return result;
    }

    private List<TicketExportColumn> resolveTicketColumns(String fields) {
        if (fields == null || fields.isBlank()) {
            return TICKET_COLUMNS;
        }
        Set<String> requested = Arrays.stream(fields.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            throw new BusinessException("至少选择一项工单导出字段");
        }
        List<TicketExportColumn> columns = TICKET_COLUMNS.stream()
                .filter(column -> requested.contains(column.key()))
                .toList();
        if (columns.size() != requested.size()) {
            throw new BusinessException("包含不支持的工单导出字段");
        }
        return columns;
    }

    public byte[] dashboardReportCsv(Long tenantId, LocalDate from, LocalDate to, Long exportActorId) {
        DashboardReport report = dashboardService.report(tenantId, from, to);
        StringBuilder csv = new StringBuilder(BOM);
        appendRow(csv, "报表区间", "开始日期", "结束日期");
        appendRow(csv, "时间范围", report.from(), report.to());
        csv.append('\n');
        appendRow(csv, "核心指标", "数值");
        appendRow(csv, "周期新增", report.createdTickets());
        appendRow(csv, "周期已解决", report.resolvedTickets());
        appendRow(csv, "当前活跃", report.activeTickets());
        appendRow(csv, "当前逾期", report.overdueTickets());
        appendRow(csv, "平均解决时长（小时）", report.averageResolutionHours());
        appendRow(csv, "SLA 达成率（%）", report.slaComplianceRate());
        appendRow(csv, "SLA 已解决样本", report.slaResolvedSample());
        csv.append('\n');
        appendRow(csv, "处理人表现", "处理人", "新增工单", "已解决", "处理中", "平均解决时长（小时）", "SLA 达成率（%）");
        for (DashboardReport.AssigneeMetric metric : report.assignees()) {
            appendRow(csv, "处理人", metric.assigneeId() == null ? "未分配" : metric.assigneeId(),
                    metric.createdTickets(), metric.resolvedTickets(), metric.activeTickets(),
                    metric.averageResolutionHours(), metric.slaComplianceRate());
        }
        csv.append('\n');
        appendRow(csv, "分类分布", "分类", "新增工单", "已解决", "处理中");
        for (DashboardReport.CategoryMetric metric : report.categories()) {
            appendRow(csv, "分类", metric.category(), metric.createdTickets(),
                    metric.resolvedTickets(), metric.activeTickets());
        }
        auditLogService.record(tenantId, exportActorId, "EXPORT", null, "EXPORT_GENERATED",
                null, null, "导出工单运营报表 CSV，时间范围：" + report.from() + " 至 " + report.to());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    public byte[] auditLogsCsv(Long tenantId, Long actorId, String action,
                               LocalDateTime from, LocalDateTime to,
                               Integer limit, Long exportActorId) {
        return auditLogsCsv(tenantId, actorId, action, null, from, to, limit, exportActorId);
    }

    public byte[] auditLogsCsv(Long tenantId, Long actorId, String action, String actions,
                               LocalDateTime from, LocalDateTime to,
                               Integer limit, Long exportActorId) {
        Set<String> selectedActions = resolveAuditActions(action, actions);
        List<AuditLog> logs;
        if (actions == null || actions.isBlank()) {
            logs = auditLogService.search(tenantId, actorId, action, from, to, limit);
        } else {
            if (selectedActions.isEmpty()) throw new BusinessException("至少选择一项审计事件");
            logs = auditLogService.search(tenantId, actorId, selectedActions, from, to, limit);
        }
        StringBuilder csv = new StringBuilder(BOM)
                .append("日志ID,操作者ID,资源类型,资源ID,动作,说明,变更前,变更后,发生时间\n");
        for (AuditLog log : logs) {
            appendRow(csv, log.getId(), log.getActorId(), log.getResourceType(), log.getResourceId(),
                    log.getAction(), log.getDetail(), log.getBeforeValue(), log.getAfterValue(), log.getCreatedAt());
        }
        String eventSummary = selectedActions.isEmpty()
                ? (action == null || action.isBlank() ? "全部事件" : action.trim().toUpperCase(Locale.ROOT))
                : String.join("、", selectedActions);
        auditLogService.record(tenantId, exportActorId, "EXPORT", null, "EXPORT_GENERATED",
                null, null, "导出审计日志 CSV，共 " + logs.size() + " 条，事件：" + eventSummary);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Set<String> resolveAuditActions(String action, String actions) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (action != null && !action.isBlank()) result.add(action.trim().toUpperCase(Locale.ROOT));
        if (actions != null && !actions.isBlank()) {
            Arrays.stream(actions.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .forEach(result::add);
        }
        return result;
    }

    private void appendHeader(StringBuilder csv, List<TicketExportColumn> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) csv.append(',');
            csv.append(columns.get(i).label());
        }
        csv.append('\n');
    }
    private void appendRow(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(escape(values[i]));
        }
        csv.append('\n');
    }

    private String escape(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private record TicketExportColumn(String key, String label, Function<Ticket, Object> value) {
    }
}
