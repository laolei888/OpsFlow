package com.opsflow.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.security.SensitiveDataSanitizer;
import com.opsflow.webhook.WebhookEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final SensitiveDataSanitizer sensitiveDataSanitizer;
    private final WebhookEventPublisher webhookEventPublisher;

    @Transactional
    public void record(Long tenantId, Long actorId, String resourceType, Long resourceId,
                       String action, Object beforeValue, Object afterValue, String detail) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActorId(actorId);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setAction(action);
        log.setBeforeValue(toJson(beforeValue));
        log.setAfterValue(toJson(afterValue));
        log.setDetail(sensitiveDataSanitizer.sanitizeText(detail));
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
        if (webhookEventPublisher != null) {
            webhookEventPublisher.publish(log);
        }
    }

    public List<AuditLog> listForTicket(Long ticketId, Long tenantId) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId)
                .eq(AuditLog::getResourceType, "TICKET")
                .eq(AuditLog::getResourceId, ticketId)
                .orderByAsc(AuditLog::getCreatedAt));
    }

    public List<AuditLog> search(Long tenantId, Long actorId, String action,
                                 LocalDateTime from, LocalDateTime to, Integer limit) {
        return search(tenantId, actorId, action == null ? null : List.of(action), from, to, limit);
    }

    public List<AuditLog> search(Long tenantId, Long actorId, Collection<String> actions,
                                 LocalDateTime from, LocalDateTime to, Integer limit) {
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId)
                .orderByDesc(AuditLog::getCreatedAt)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + safeLimit);
        if (actorId != null) query.eq(AuditLog::getActorId, actorId);
        if (actions != null) {
            List<String> normalizedActions = actions.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (!normalizedActions.isEmpty()) query.in(AuditLog::getAction, normalizedActions);
        }
        if (from != null) query.ge(AuditLog::getCreatedAt, from);
        if (to != null) query.le(AuditLog::getCreatedAt, to);
        return auditLogMapper.selectList(query);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(sensitiveDataSanitizer.sanitize(value));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("审计快照序列化失败");
        }
    }
}
