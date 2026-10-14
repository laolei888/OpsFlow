package com.opsflow.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);

    private final TicketMapper ticketMapper;

    public DashboardOverview overview(Long tenantId, Long requesterId) {
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .orderByDesc(Ticket::getCreatedAt);
        if (requesterId != null) query.eq(Ticket::getRequesterId, requesterId);

        List<Ticket> tickets = ticketMapper.selectList(query);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        long active = tickets.stream().filter(ticket -> ACTIVE_STATUSES.contains(ticket.getStatus())).count();
        long overdue = tickets.stream().filter(ticket -> isActive(ticket) && ticket.getDueAt() != null
                && ticket.getDueAt().isBefore(now)).count();
        long dueSoon = tickets.stream().filter(ticket -> isActive(ticket) && ticket.getDueAt() != null
                && !ticket.getDueAt().isBefore(now) && !ticket.getDueAt().isAfter(now.plusHours(24))).count();
        long resolved = tickets.stream().filter(ticket -> ticket.getStatus() == TicketStatus.RESOLVED
                || ticket.getStatus() == TicketStatus.CLOSED).count();
        long urgent = tickets.stream().filter(ticket -> ticket.getPriority() == TicketPriority.URGENT).count();
        long unassigned = tickets.stream().filter(ticket -> ticket.getAssigneeId() == null).count();
        long createdToday = tickets.stream().filter(ticket -> ticket.getCreatedAt() != null
                && ticket.getCreatedAt().toLocalDate().equals(today)).count();
        long resolvedToday = tickets.stream().filter(ticket -> (ticket.getStatus() == TicketStatus.RESOLVED
                || ticket.getStatus() == TicketStatus.CLOSED) && ticket.getUpdatedAt() != null
                && ticket.getUpdatedAt().toLocalDate().equals(today)).count();

        List<DashboardOverview.Metric> statuses = Arrays.stream(TicketStatus.values())
                .map(status -> new DashboardOverview.Metric(status.name(), statusLabel(status),
                        tickets.stream().filter(ticket -> ticket.getStatus() == status).count()))
                .toList();
        List<DashboardOverview.Metric> priorities = Arrays.stream(TicketPriority.values())
                .map(priority -> new DashboardOverview.Metric(priority.name(), priorityLabel(priority),
                        tickets.stream().filter(ticket -> ticket.getPriority() == priority).count()))
                .toList();

        return new DashboardOverview(tickets.size(), active, overdue, dueSoon, resolved, urgent,
                unassigned, tickets.size() - unassigned, createdToday, resolvedToday, statuses, priorities);
    }

    public List<DashboardTrend> trends(Long tenantId, Long requesterId, Integer days) {
        int safeDays = days == null ? 14 : Math.max(7, Math.min(days, 31));
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(safeDays - 1L);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .and(wrapper -> wrapper
                        .ge(Ticket::getCreatedAt, start).lt(Ticket::getCreatedAt, end)
                        .or()
                        .ge(Ticket::getUpdatedAt, start).lt(Ticket::getUpdatedAt, end));
        if (requesterId != null) query.eq(Ticket::getRequesterId, requesterId);
        List<Ticket> tickets = ticketMapper.selectList(query);

        MapBuilder counts = new MapBuilder(startDate, safeDays);
        for (Ticket ticket : tickets) {
            if (ticket.getCreatedAt() != null) counts.created(ticket.getCreatedAt().toLocalDate());
            if ((ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.CLOSED)
                    && ticket.getUpdatedAt() != null) {
                counts.resolved(ticket.getUpdatedAt().toLocalDate());
            }
        }
        return counts.toList();
    }

    public DashboardReport report(Long tenantId, LocalDate from, LocalDate to) {
        LocalDate safeTo = to == null ? LocalDate.now() : to;
        LocalDate safeFrom = from == null ? safeTo.minusDays(13) : from;
        if (safeFrom.isAfter(safeTo)) {
            throw new BusinessException("报表开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(safeFrom, safeTo) > 90) {
            throw new BusinessException("报表时间范围不能超过 91 天");
        }

        LocalDateTime start = safeFrom.atStartOfDay();
        LocalDateTime end = safeTo.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .and(wrapper -> wrapper
                        .ge(Ticket::getCreatedAt, start).lt(Ticket::getCreatedAt, end)
                        .or()
                        .ge(Ticket::getUpdatedAt, start).lt(Ticket::getUpdatedAt, end)
                        .or()
                        .in(Ticket::getStatus, ACTIVE_STATUSES));
        List<Ticket> tickets = ticketMapper.selectList(query);

        long createdTickets = 0;
        long resolvedTickets = 0;
        long activeTickets = 0;
        long overdueTickets = 0;
        long slaCompliant = 0;
        long slaResolvedSample = 0;
        long resolutionSamples = 0;
        double resolutionHours = 0;
        HashMap<Long, ReportAccumulator> assigneeStats = new HashMap<>();
        HashMap<String, ReportAccumulator> categoryStats = new HashMap<>();

        for (Ticket ticket : tickets) {
            boolean createdInRange = inRange(ticket.getCreatedAt(), start, end);
            boolean resolvedInRange = isResolved(ticket) && inRange(ticket.getUpdatedAt(), start, end);
            boolean active = isActive(ticket);
            ReportAccumulator assignee = assigneeStats.computeIfAbsent(ticket.getAssigneeId(),
                    ignored -> new ReportAccumulator());
            ReportAccumulator category = categoryStats.computeIfAbsent(categoryOf(ticket),
                    ignored -> new ReportAccumulator());

            if (createdInRange) {
                createdTickets++;
                assignee.createdTickets++;
                category.createdTickets++;
            }
            if (resolvedInRange) {
                resolvedTickets++;
                assignee.resolvedTickets++;
                category.resolvedTickets++;
                if (ticket.getCreatedAt() != null && ticket.getUpdatedAt() != null) {
                    double hours = Math.max(0, ChronoUnit.SECONDS.between(
                            ticket.getCreatedAt(), ticket.getUpdatedAt()) / 3600.0);
                    resolutionHours += hours;
                    resolutionSamples++;
                    assignee.resolutionHours += hours;
                    assignee.resolutionSamples++;
                }
                if (ticket.getDueAt() != null && ticket.getUpdatedAt() != null) {
                    slaResolvedSample++;
                    assignee.slaSamples++;
                    if (!ticket.getUpdatedAt().isAfter(ticket.getDueAt())) {
                        slaCompliant++;
                        assignee.slaCompliant++;
                    }
                }
            }
            if (active) {
                activeTickets++;
                assignee.activeTickets++;
                category.activeTickets++;
                if (ticket.getDueAt() != null && ticket.getDueAt().isBefore(now)) {
                    overdueTickets++;
                }
            }
        }

        List<DashboardReport.AssigneeMetric> assignees = assigneeStats.entrySet().stream()
                .sorted((left, right) -> {
                    int resolvedCompare = Long.compare(right.getValue().resolvedTickets, left.getValue().resolvedTickets);
                    if (resolvedCompare != 0) return resolvedCompare;
                    return Long.compare(right.getValue().createdTickets, left.getValue().createdTickets);
                })
                .limit(8)
                .map(entry -> new DashboardReport.AssigneeMetric(
                        entry.getKey(),
                        entry.getValue().createdTickets,
                        entry.getValue().resolvedTickets,
                        entry.getValue().activeTickets,
                        averageHours(entry.getValue().resolutionHours, entry.getValue().resolutionSamples),
                        rate(entry.getValue().slaCompliant, entry.getValue().slaSamples)))
                .toList();

        List<DashboardReport.CategoryMetric> categories = categoryStats.entrySet().stream()
                .sorted((left, right) -> Long.compare(
                        right.getValue().createdTickets, left.getValue().createdTickets))
                .limit(6)
                .map(entry -> new DashboardReport.CategoryMetric(
                        entry.getKey(),
                        entry.getValue().createdTickets,
                        entry.getValue().resolvedTickets,
                        entry.getValue().activeTickets))
                .toList();

        return new DashboardReport(
                safeFrom,
                safeTo,
                createdTickets,
                resolvedTickets,
                activeTickets,
                overdueTickets,
                averageHours(resolutionHours, resolutionSamples),
                rate(slaCompliant, slaResolvedSample),
                slaResolvedSample,
                assignees,
                categories);
    }

    private boolean inRange(LocalDateTime value, LocalDateTime start, LocalDateTime end) {
        return value != null && !value.isBefore(start) && value.isBefore(end);
    }

    private boolean isResolved(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.CLOSED;
    }

    private String categoryOf(Ticket ticket) {
        return ticket.getCategory() == null || ticket.getCategory().isBlank() ? "未分类" : ticket.getCategory();
    }

    private double averageHours(double totalHours, long samples) {
        if (samples == 0) return 0;
        return Math.round(totalHours / samples * 10.0) / 10.0;
    }

    private int rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (int) Math.round(numerator * 100.0 / denominator);
    }

    private static final class ReportAccumulator {
        private long createdTickets;
        private long resolvedTickets;
        private long activeTickets;
        private double resolutionHours;
        private long resolutionSamples;
        private long slaCompliant;
        private long slaSamples;
    }
    private static final class MapBuilder {
        private final Map<LocalDate, long[]> values = new java.util.LinkedHashMap<>();

        private MapBuilder(LocalDate startDate, int days) {
            for (int i = 0; i < days; i++) values.put(startDate.plusDays(i), new long[2]);
        }

        private void created(LocalDate date) {
            long[] counts = values.get(date);
            if (counts != null) counts[0]++;
        }

        private void resolved(LocalDate date) {
            long[] counts = values.get(date);
            if (counts != null) counts[1]++;
        }

        private List<DashboardTrend> toList() {
            return values.entrySet().stream()
                    .map(entry -> new DashboardTrend(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                    .toList();
        }
    }

    private boolean isActive(Ticket ticket) {
        return ACTIVE_STATUSES.contains(ticket.getStatus());
    }

    private String statusLabel(TicketStatus status) {
        return switch (status) {
            case OPEN -> "待处理";
            case IN_PROGRESS -> "处理中";
            case WAITING_CONFIRMATION -> "待确认";
            case RESOLVED -> "已解决";
            case CLOSED -> "已关闭";
            case REJECTED -> "已驳回";
        };
    }

    private String priorityLabel(TicketPriority priority) {
        return switch (priority) {
            case LOW -> "低";
            case MEDIUM -> "中";
            case HIGH -> "高";
            case URGENT -> "紧急";
        };
    }
}
