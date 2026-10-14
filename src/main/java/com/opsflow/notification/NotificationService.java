package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.common.BusinessException;
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
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);

    private final NotificationMapper notificationMapper;
    private final TicketMapper ticketMapper;
    private final AuthService authService;
    private final OnCallRecipientResolver onCallRecipientResolver;

    public List<Notification> list(Long recipientId, Long tenantId, boolean unreadOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<Notification> query = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT " + safeLimit);
        if (unreadOnly) query.isNull(Notification::getReadAt);
        return notificationMapper.selectList(query);
    }

    public long unreadCount(Long recipientId, Long tenantId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId)
                .isNull(Notification::getReadAt));
    }

    @Transactional
    public void markRead(Long id, Long recipientId, Long tenantId) {
        Notification notification = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId));
        if (notification == null) throw new BusinessException("通知不存在");
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(notification);
        }
    }

    @Transactional
    public boolean notifyIfAbsent(Long tenantId, Long recipientId, String type,
                                  String title, String content, String dedupeKey) {
        return notifyIfAbsent(tenantId, recipientId, null, type, title, content, dedupeKey);
    }

    @Transactional
    public boolean notifyIfAbsent(Long tenantId, Long recipientId, Long ticketId, String type,
                                  String title, String content, String dedupeKey) {
        LambdaQueryWrapper<Notification> exists = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getDedupeKey, dedupeKey);
        if (notificationMapper.selectCount(exists) > 0) return false;

        Notification notification = new Notification();
        notification.setTenantId(tenantId);
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticketId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        return true;
    }
    public int markAllRead(Long recipientId, Long tenantId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

    @Transactional
    public int scanTenant(Long tenantId) {
        List<StaffSummary> staff = authService.staffMembers(tenantId);
        if (staff.isEmpty()) return 0;

        List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getStatus, ACTIVE_STATUSES)
                .isNotNull(Ticket::getDueAt));
        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        for (Ticket ticket : tickets) {
            Alert alert = alertFor(ticket, now);
            if (alert == null) continue;

            Set<Long> recipients = new HashSet<>();
            if (ticket.getAssigneeId() != null) {
                recipients.add(ticket.getAssigneeId());
            } else {
                recipients.addAll(onCallRecipientResolver.activePrimaryRecipients(tenantId, now));
                if (recipients.isEmpty()) staff.forEach(item -> recipients.add(item.id()));
            }
            for (Long recipientId : recipients) {
                if (createIfAbsent(ticket, recipientId, alert)) created++;
            }
        }
        return created;
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
            int created = tenantIds.stream().mapToInt(this::scanTenant).sum();
            if (created > 0) log.info("SLA scan created {} notification(s)", created);
        } catch (Exception ex) {
            log.error("SLA notification scan failed", ex);
        }
    }

    private boolean createIfAbsent(Ticket ticket, Long recipientId, Alert alert) {
        LambdaQueryWrapper<Notification> exists = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, ticket.getTenantId())
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getDedupeKey, alert.dedupeKey());
        if (notificationMapper.selectCount(exists) > 0) return false;

        Notification notification = new Notification();
        notification.setTenantId(ticket.getTenantId());
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticket.getId());
        notification.setType(alert.type());
        notification.setTitle(alert.title());
        notification.setContent(alert.content());
        notification.setDedupeKey(alert.dedupeKey());
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        return true;
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
