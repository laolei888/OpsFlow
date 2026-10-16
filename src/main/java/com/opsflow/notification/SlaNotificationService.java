package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.oncall.OnCallRecipientResolver;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SlaNotificationService {
    private static final Logger log = LoggerFactory.getLogger(SlaNotificationService.class);
    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);

    private final TicketMapper ticketMapper;
    private final AuthService authService;
    private final OnCallRecipientResolver onCallRecipientResolver;
    private final AsyncNotificationPublisher notificationPublisher;

    @Transactional
    public int scanTenant(Long tenantId) {
        List<StaffSummary> staff = authService.staffMembers(tenantId);
        if (staff.isEmpty()) return 0;
        List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getStatus, ACTIVE_STATUSES)
                .isNotNull(Ticket::getDueAt));
        LocalDateTime now = LocalDateTime.now();
        int queued = 0;
        for (Ticket ticket : tickets) {
            Alert alert = alertFor(ticket, now);
            if (alert == null) continue;
            Set<Long> recipients = recipients(ticket, staff, tenantId, now);
            for (Long recipientId : recipients) {
                if (notificationPublisher.enqueue(ticket.getTenantId(), recipientId, ticket.getId(),
                        alert.type(), alert.title(), alert.content(), alert.dedupeKey(),
                        "TICKET", ticket.getId())) queued++;
            }
        }
        return queued;
    }

    @Scheduled(
            fixedDelayString = "${opsflow.sla.scan-interval-ms:300000}",
            initialDelayString = "${opsflow.sla.initial-delay-ms:15000}")
    public void scheduledScan() {
        try {
            List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                    .in(Ticket::getStatus, ACTIVE_STATUSES)
                    .isNotNull(Ticket::getDueAt));
            Set<Long> tenantIds = new HashSet<>();
            tickets.forEach(ticket -> tenantIds.add(ticket.getTenantId()));
            int queued = tenantIds.stream().mapToInt(this::scanTenant).sum();
            if (queued > 0) log.info("SLA scan queued {} asynchronous notification(s)", queued);
        } catch (Exception ex) {
            log.error("SLA asynchronous notification scan failed", ex);
        }
    }

    private Set<Long> recipients(Ticket ticket, List<StaffSummary> staff, Long tenantId, LocalDateTime now) {
        Set<Long> recipients = new HashSet<>();
        if (ticket.getAssigneeId() != null) {
            recipients.add(ticket.getAssigneeId());
        } else {
            recipients.addAll(onCallRecipientResolver.activePrimaryRecipients(tenantId, now));
            if (recipients.isEmpty()) staff.forEach(item -> recipients.add(item.id()));
        }
        return recipients;
    }

    private Alert alertFor(Ticket ticket, LocalDateTime now) {
        if (ticket.getDueAt().isBefore(now)) {
            return new Alert("SLA_OVERDUE", "SLA 已逾期",
                    "工单 " + ticket.getTicketNo() + " 已超过截止时间，请立即处理。",
                    "SLA_OVERDUE:" + ticket.getId() + ":" + ticket.getDueAt());
        }
        if (!ticket.getDueAt().isAfter(now.plusHours(24))) {
            return new Alert("SLA_DUE_SOON", "SLA 即将到期",
                    "工单 " + ticket.getTicketNo() + " 将在 24 小时内到期，请安排处理。",
                    "SLA_DUE_SOON:" + ticket.getId() + ":" + ticket.getDueAt());
        }
        return null;
    }

    private record Alert(String type, String title, String content, String dedupeKey) { }
}
