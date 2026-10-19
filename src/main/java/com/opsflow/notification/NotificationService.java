package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationMapper notificationMapper;
    private final NotificationDeliveryService deliveryService;

    public List<Notification> list(Long recipientId, Long tenantId, boolean unreadOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<Notification> query = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getInAppEnabled, true)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT " + safeLimit);
        if (unreadOnly) query.isNull(Notification::getReadAt);
        return notificationMapper.selectList(query);
    }

    public long unreadCount(Long recipientId, Long tenantId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getInAppEnabled, true)
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
        NotificationPreferenceService.ResolvedPreference preference = deliveryService.resolve(
                tenantId, recipientId, type);
        notification.setTenantId(tenantId);
        notification.setRecipientId(recipientId);
        notification.setTicketId(ticketId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setDedupeKey(dedupeKey);
        notification.setInAppEnabled(preference.inAppEnabled());
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        deliveryService.plan(notification, preference);
        return true;
    }
    public int markAllRead(Long recipientId, Long tenantId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .eq(Notification::getTenantId, tenantId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

}
