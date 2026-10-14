package com.opsflow.oncall;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentSeverity;
import com.opsflow.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OnCallEscalationService {
    private static final Logger log = LoggerFactory.getLogger(OnCallEscalationService.class);

    private final OnCallEscalationPolicyMapper policyMapper;
    private final OnCallEscalationStepMapper stepMapper;
    private final OnCallEscalationEventMapper eventMapper;
    private final OnCallEscalationDeliveryMapper deliveryMapper;
    private final OnCallRecipientResolver recipientResolver;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Transactional
    public void triggerIncident(Incident incident) {
        if (incident == null || incident.getId() == null || incident.getSeverity() == null) return;
        Long tenantId = incident.getTenantId();
        LocalDateTime now = LocalDateTime.now();
        List<OnCallEscalationPolicy> policies = policyMapper.selectList(new LambdaQueryWrapper<OnCallEscalationPolicy>()
                .eq(OnCallEscalationPolicy::getTenantId, tenantId)
                .eq(OnCallEscalationPolicy::getEnabled, true)
                .orderByAsc(OnCallEscalationPolicy::getId));
        for (OnCallEscalationPolicy policy : policies) {
            if (!matchesSeverity(incident.getSeverity(), policy.getMinimumSeverity())) continue;
            List<OnCallEscalationStep> steps = stepsForPolicy(policy.getId(), tenantId);
            if (steps.isEmpty()) continue;
            OnCallEscalationEvent existing = eventMapper.selectOne(new LambdaQueryWrapper<OnCallEscalationEvent>()
                    .eq(OnCallEscalationEvent::getTenantId, tenantId)
                    .eq(OnCallEscalationEvent::getIncidentId, incident.getId())
                    .eq(OnCallEscalationEvent::getPolicyId, policy.getId()));
            if (existing != null) continue;

            OnCallEscalationEvent event = new OnCallEscalationEvent();
            event.setTenantId(tenantId);
            event.setIncidentId(incident.getId());
            event.setPolicyId(policy.getId());
            event.setIncidentNo(incident.getIncidentNo());
            event.setIncidentTitle(incident.getTitle());
            event.setSeverity(incident.getSeverity());
            event.setStatus(OnCallEscalationEventStatus.ACTIVE);
            event.setCurrentStep(0);
            event.setNextEscalateAt(now);
            event.setCreatedAt(now);
            event.setUpdatedAt(now);
            eventMapper.insert(event);
            auditLogService.record(tenantId, incident.getDeclaredBy(), "ONCALL_ESCALATION", event.getId(),
                    "ONCALL_ESCALATION_TRIGGERED", null, event,
                    "重大事件触发值班升级策略：「" + policy.getName() + "」");
        }
    }

    @Transactional
    public void resolveIncident(Incident incident, Long actorId, boolean cancelled) {
        if (incident == null || incident.getId() == null) return;
        List<OnCallEscalationEvent> events = eventMapper.selectList(new LambdaQueryWrapper<OnCallEscalationEvent>()
                .eq(OnCallEscalationEvent::getTenantId, incident.getTenantId())
                .eq(OnCallEscalationEvent::getIncidentId, incident.getId())
                .in(OnCallEscalationEvent::getStatus, OnCallEscalationEventStatus.ACTIVE,
                        OnCallEscalationEventStatus.EXHAUSTED));
        LocalDateTime now = LocalDateTime.now();
        for (OnCallEscalationEvent event : events) {
            OnCallEscalationEventStatus before = event.getStatus();
            event.setStatus(cancelled ? OnCallEscalationEventStatus.CANCELLED : OnCallEscalationEventStatus.RESOLVED);
            event.setNextEscalateAt(null);
            event.setResolvedAt(now);
            event.setUpdatedAt(now);
            eventMapper.updateById(event);
            auditLogService.record(event.getTenantId(), actorId, "ONCALL_ESCALATION", event.getId(),
                    cancelled ? "ONCALL_ESCALATION_CANCELLED" : "ONCALL_ESCALATION_RESOLVED",
                    Map.of("status", before.name()), Map.of("status", event.getStatus().name()),
                    cancelled ? "重大事件取消，停止值班升级" : "重大事件已解决，停止值班升级");
        }
    }

    public List<EscalationEventView> listEvents(Long tenantId, Integer limit) {
        int safeLimit = limit == null ? 80 : Math.max(1, Math.min(limit, 200));
        List<OnCallEscalationEvent> events = eventMapper.selectList(new LambdaQueryWrapper<OnCallEscalationEvent>()
                .eq(OnCallEscalationEvent::getTenantId, tenantId)
                .orderByDesc(OnCallEscalationEvent::getCreatedAt)
                .orderByDesc(OnCallEscalationEvent::getId)
                .last("LIMIT " + safeLimit));
        if (events.isEmpty()) return List.of();
        Set<Long> policyIds = events.stream().map(OnCallEscalationEvent::getPolicyId).collect(Collectors.toSet());
        Map<Long, OnCallEscalationPolicy> policies = policyMapper.selectBatchIds(policyIds).stream()
                .filter(policy -> tenantId.equals(policy.getTenantId()))
                .collect(Collectors.toMap(OnCallEscalationPolicy::getId, policy -> policy));
        Map<Long, List<OnCallEscalationDelivery>> deliveries = new HashMap<>();
        for (OnCallEscalationEvent event : events) {
            deliveries.put(event.getId(), deliveryMapper.selectList(new LambdaQueryWrapper<OnCallEscalationDelivery>()
                    .eq(OnCallEscalationDelivery::getTenantId, tenantId)
                    .eq(OnCallEscalationDelivery::getEscalationEventId, event.getId())
                    .orderByDesc(OnCallEscalationDelivery::getCreatedAt)
                    .orderByDesc(OnCallEscalationDelivery::getId)
                    .last("LIMIT 100")));
        }
        return events.stream().map(event -> new EscalationEventView(event,
                policies.get(event.getPolicyId()) == null ? "已删除策略" : policies.get(event.getPolicyId()).getName(),
                deliveries.getOrDefault(event.getId(), List.of()))).toList();
    }

    @Scheduled(
            fixedDelayString = "${opsflow.oncall.escalation-dispatch-interval-ms:30000}",
            initialDelayString = "${opsflow.oncall.initial-delay-ms:25000}")
    @Transactional
    public void dispatchDueEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<OnCallEscalationEvent> dueEvents = eventMapper.selectList(new LambdaQueryWrapper<OnCallEscalationEvent>()
                .eq(OnCallEscalationEvent::getStatus, OnCallEscalationEventStatus.ACTIVE)
                .isNotNull(OnCallEscalationEvent::getNextEscalateAt)
                .le(OnCallEscalationEvent::getNextEscalateAt, now)
                .orderByAsc(OnCallEscalationEvent::getNextEscalateAt)
                .orderByAsc(OnCallEscalationEvent::getId)
                .last("LIMIT 100"));
        for (OnCallEscalationEvent dueEvent : dueEvents) {
            try {
                dispatchEvent(dueEvent.getId(), now);
            } catch (Exception ex) {
                log.error("On-call escalation dispatch failed for event {}", dueEvent.getId(), ex);
            }
        }
    }

    private void dispatchEvent(Long eventId, LocalDateTime now) {
        OnCallEscalationEvent event = eventMapper.selectForUpdate(eventId);
        if (event == null || event.getStatus() != OnCallEscalationEventStatus.ACTIVE
                || event.getNextEscalateAt() == null || event.getNextEscalateAt().isAfter(now)) return;
        List<OnCallEscalationStep> steps = stepsForPolicy(event.getPolicyId(), event.getTenantId());
        OnCallEscalationStep step = nextStep(steps, event.getCurrentStep());
        if (step == null) {
            exhaust(event, now);
            return;
        }
        Set<Long> recipients = recipients(event.getTenantId(), step, now);
        if (recipients.isEmpty()) {
            event.setLastError("当前没有可用升级接收人，将在 5 分钟后重试");
            event.setNextEscalateAt(now.plusMinutes(5));
            event.setUpdatedAt(now);
            eventMapper.updateById(event);
            return;
        }
        int sent = 0;
        for (Long recipientId : recipients) {
            boolean created = notificationService.notifyIfAbsent(event.getTenantId(), recipientId,
                    "ONCALL_ESCALATION", "重大事件值班升级 · L" + step.getStepNo(),
                    "事件「" + event.getIncidentTitle() + "」(" + event.getIncidentNo() + ") 当前等级："
                            + event.getSeverity() + "，请立即响应。",
                    "ONCALL_ESCALATION:" + event.getId() + ":" + step.getStepNo() + ":" + recipientId);
            OnCallEscalationDelivery delivery = new OnCallEscalationDelivery();
            delivery.setTenantId(event.getTenantId());
            delivery.setEscalationEventId(event.getId());
            delivery.setStepNo(step.getStepNo());
            delivery.setRecipientId(recipientId);
            delivery.setTargetType(step.getTargetType());
            delivery.setStatus(created ? OnCallEscalationDeliveryStatus.SENT : OnCallEscalationDeliveryStatus.SUPPRESSED);
            delivery.setDetail(created ? "已写入通知中心" : "命中通知去重规则");
            delivery.setCreatedAt(now);
            deliveryMapper.insert(delivery);
            if (created) sent++;
        }
        OnCallEscalationStep next = nextStep(steps, step.getStepNo());
        event.setCurrentStep(step.getStepNo());
        event.setLastNotifiedAt(now);
        event.setLastError(null);
        event.setUpdatedAt(now);
        if (next == null) {
            event.setStatus(OnCallEscalationEventStatus.EXHAUSTED);
            event.setNextEscalateAt(null);
        } else {
            event.setNextEscalateAt(now.plusMinutes(next.getDelayMinutes() == null ? 0 : next.getDelayMinutes()));
        }
        eventMapper.updateById(event);
        auditLogService.record(event.getTenantId(), null, "ONCALL_ESCALATION", event.getId(),
                "ONCALL_ESCALATION_DISPATCHED", Map.of("step", step.getStepNo()),
                Map.of("step", step.getStepNo(), "recipients", recipients.size(), "notifications", sent),
                "派发值班升级第 " + step.getStepNo() + " 级通知");
    }

    private Set<Long> recipients(Long tenantId, OnCallEscalationStep step, LocalDateTime now) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (step.getTargetType() == OnCallEscalationTargetType.USER) {
            if (step.getTargetUserId() != null && authService.isStaffMember(step.getTargetUserId(), tenantId)) {
                recipients.add(step.getTargetUserId());
            }
        } else if (step.getTargetType() == OnCallEscalationTargetType.ON_CALL) {
            recipients.addAll(recipientResolver.activeRecipients(tenantId, step.getScheduleId(), now));
            if (recipients.isEmpty()) {
                authService.staffMembers(tenantId).forEach(member -> recipients.add(member.id()));
            }
        } else {
            authService.staffMembers(tenantId).forEach(member -> recipients.add(member.id()));
        }
        return recipients;
    }

    private List<OnCallEscalationStep> stepsForPolicy(Long policyId, Long tenantId) {
        return stepMapper.selectList(new LambdaQueryWrapper<OnCallEscalationStep>()
                .eq(OnCallEscalationStep::getTenantId, tenantId)
                .eq(OnCallEscalationStep::getPolicyId, policyId)
                .orderByAsc(OnCallEscalationStep::getStepNo)
                .orderByAsc(OnCallEscalationStep::getId));
    }

    private OnCallEscalationStep nextStep(List<OnCallEscalationStep> steps, Integer currentStep) {
        int current = currentStep == null ? 0 : currentStep;
        return steps.stream().filter(step -> step.getStepNo() != null && step.getStepNo() > current)
                .findFirst().orElse(null);
    }

    private void exhaust(OnCallEscalationEvent event, LocalDateTime now) {
        event.setStatus(OnCallEscalationEventStatus.EXHAUSTED);
        event.setNextEscalateAt(null);
        event.setUpdatedAt(now);
        eventMapper.updateById(event);
    }

    private boolean matchesSeverity(IncidentSeverity severity, IncidentSeverity minimumSeverity) {
        IncidentSeverity threshold = minimumSeverity == null ? IncidentSeverity.SEV2 : minimumSeverity;
        return severity.ordinal() <= threshold.ordinal();
    }

    public record EscalationEventView(OnCallEscalationEvent event, String policyName,
                                      List<OnCallEscalationDelivery> deliveries) { }
}
