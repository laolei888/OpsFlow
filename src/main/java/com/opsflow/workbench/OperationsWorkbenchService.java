package com.opsflow.workbench;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.alert.AlertSeverity;
import com.opsflow.alert.AlertStatus;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.catalog.ServiceRequest;
import com.opsflow.catalog.ServiceRequestMapper;
import com.opsflow.catalog.ServiceRequestStatus;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.change.ChangeRisk;
import com.opsflow.change.ChangeStatus;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.incident.IncidentSeverity;
import com.opsflow.incident.IncidentStatus;
import com.opsflow.knowledge.KnowledgeArticle;
import com.opsflow.knowledge.KnowledgeMapper;
import com.opsflow.knowledge.KnowledgeStatus;
import com.opsflow.postmortem.IncidentPostmortem;
import com.opsflow.postmortem.IncidentPostmortemMapper;
import com.opsflow.postmortem.PostmortemStatus;
import com.opsflow.slo.SloBreachEvent;
import com.opsflow.slo.SloBreachEventMapper;
import com.opsflow.slo.SloBreachLevel;
import com.opsflow.slo.SloBreachStatus;
import com.opsflow.statuspage.StatusPageIncident;
import com.opsflow.statuspage.StatusPageIncidentImpact;
import com.opsflow.statuspage.StatusPageIncidentMapper;
import com.opsflow.ticket.TicketPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OperationsWorkbenchService {
    private static final int SOURCE_LIMIT = 200;
    private static final Set<IncidentStatus> ACTIVE_INCIDENT_STATUSES = Set.of(
            IncidentStatus.DECLARED, IncidentStatus.INVESTIGATING,
            IncidentStatus.MITIGATING, IncidentStatus.MONITORING);

    private final ServiceRequestMapper serviceRequestMapper;
    private final ChangeRequestMapper changeRequestMapper;
    private final MonitoringAlertMapper alertMapper;
    private final IncidentMapper incidentMapper;
    private final SloBreachEventMapper breachMapper;
    private final StatusPageIncidentMapper statusIncidentMapper;
    private final IncidentPostmortemMapper postmortemMapper;
    private final KnowledgeMapper knowledgeMapper;

    public OperationsWorkbenchView overview(Long tenantId, boolean admin, String types,
                                             String priorities, Integer requestedLimit) {
        List<OperationsWorkbenchView.WorkItem> all = new ArrayList<>();
        if (admin) {
            appendServiceApprovals(all, tenantId);
            appendChangeApprovals(all, tenantId);
            appendUnpublishedNotices(all, tenantId);
            appendPostmortemReviews(all, tenantId);
            appendKnowledgeReviews(all, tenantId);
            appendOverdueKnowledgeReviews(all, tenantId);
        }
        appendFiringAlerts(all, tenantId);
        appendActiveIncidents(all, tenantId);
        appendReliabilityBreaches(all, tenantId);
        all.sort(Comparator.comparingInt((OperationsWorkbenchView.WorkItem item) -> priorityRank(item.priority()))
                .thenComparing(OperationsWorkbenchView.WorkItem::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OperationsWorkbenchView.WorkItem::id));

        OperationsWorkbenchView.Summary summary = summary(all);
        Set<String> typeFilter = csvFilter(types);
        Set<String> priorityFilter = csvFilter(priorities);
        int limit = Math.max(1, Math.min(requestedLimit == null ? 100 : requestedLimit, 200));
        List<OperationsWorkbenchView.WorkItem> visible = all.stream()
                .filter(item -> typeFilter.isEmpty() || typeFilter.contains(item.type()))
                .filter(item -> priorityFilter.isEmpty() || priorityFilter.contains(item.priority()))
                .limit(limit)
                .toList();
        return new OperationsWorkbenchView(summary, visible, LocalDateTime.now());
    }

    private void appendServiceApprovals(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        serviceRequestMapper.selectList(new LambdaQueryWrapper<ServiceRequest>()
                        .eq(ServiceRequest::getTenantId, tenantId)
                        .eq(ServiceRequest::getStatus, ServiceRequestStatus.PENDING_APPROVAL)
                        .orderByAsc(ServiceRequest::getCreatedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(request -> items.add(item(request.getId(), "SERVICE_APPROVAL", "服务审批",
                        request.getRequestNo(), request.getSummary(), request.getDetail(),
                        ticketPriority(request.getPriority()), request.getStatus().name(), "catalog", "处理审批",
                        true, request.getCreatedAt(), request.getUpdatedAt())));
    }

    private void appendChangeApprovals(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        changeRequestMapper.selectList(new LambdaQueryWrapper<ChangeRequest>()
                        .eq(ChangeRequest::getTenantId, tenantId)
                        .eq(ChangeRequest::getStatus, ChangeStatus.PENDING_APPROVAL)
                        .orderByAsc(ChangeRequest::getCreatedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(change -> items.add(item(change.getId(), "CHANGE_APPROVAL", "变更审批",
                        change.getChangeNo(), change.getTitle(), firstText(change.getImpactScope(), change.getDescription()),
                        changeRisk(change.getRiskLevel()), change.getStatus().name(), "changes", "评审变更",
                        true, change.getCreatedAt(), change.getUpdatedAt())));
    }

    private void appendFiringAlerts(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        alertMapper.selectList(new LambdaQueryWrapper<MonitoringAlert>()
                        .eq(MonitoringAlert::getTenantId, tenantId)
                        .eq(MonitoringAlert::getStatus, AlertStatus.FIRING)
                        .orderByAsc(MonitoringAlert::getFirstSeenAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(alert -> items.add(item(alert.getId(), "FIRING_ALERT", "监控告警",
                        alert.getAlertNo(), alert.getTitle(), alert.getDescription(), alertSeverity(alert.getSeverity()),
                        alert.getStatus().name(), "alerts", "确认告警", false,
                        alert.getFirstSeenAt(), alert.getUpdatedAt())));
    }

    private void appendActiveIncidents(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        incidentMapper.selectList(new LambdaQueryWrapper<Incident>()
                        .eq(Incident::getTenantId, tenantId)
                        .in(Incident::getStatus, ACTIVE_INCIDENT_STATUSES)
                        .orderByAsc(Incident::getStartedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(incident -> items.add(item(incident.getId(), "ACTIVE_INCIDENT", "重大事件",
                        incident.getIncidentNo(), incident.getTitle(),
                        firstText(incident.getBusinessImpact(), incident.getSummary()),
                        incidentSeverity(incident.getSeverity()), incident.getStatus().name(), "incidents", "进入指挥台",
                        false, incident.getStartedAt(), incident.getUpdatedAt())));
    }

    private void appendReliabilityBreaches(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        breachMapper.selectList(new LambdaQueryWrapper<SloBreachEvent>()
                        .eq(SloBreachEvent::getTenantId, tenantId)
                        .in(SloBreachEvent::getStatus, List.of(SloBreachStatus.OPEN, SloBreachStatus.ACKNOWLEDGED))
                        .orderByAsc(SloBreachEvent::getCreatedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(breach -> items.add(item(breach.getId(), "SLO_BREACH", "可靠性违规",
                        breach.getBreachNo(), breach.getTitle(), breachDescription(breach),
                        breach.getLevel() == SloBreachLevel.CRITICAL ? "CRITICAL" : "HIGH",
                        breach.getStatus().name(), "reliability", "查看错误预算", false,
                        breach.getCreatedAt(), breach.getUpdatedAt())));
    }

    private void appendUnpublishedNotices(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        statusIncidentMapper.selectList(new LambdaQueryWrapper<StatusPageIncident>()
                        .eq(StatusPageIncident::getTenantId, tenantId)
                        .eq(StatusPageIncident::getPublished, false)
                        .orderByAsc(StatusPageIncident::getCreatedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(notice -> items.add(item(notice.getId(), "STATUS_DRAFT", "公告待发布",
                        notice.getPublicNo(), notice.getTitle(), notice.getSummary(), statusImpact(notice.getImpact()),
                        notice.getStatus().name(), "statusPage", "审核公告", true,
                        notice.getCreatedAt(), notice.getUpdatedAt())));
    }

    private void appendPostmortemReviews(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        postmortemMapper.selectList(new LambdaQueryWrapper<IncidentPostmortem>()
                        .eq(IncidentPostmortem::getTenantId, tenantId)
                        .eq(IncidentPostmortem::getStatus, PostmortemStatus.IN_REVIEW)
                        .orderByAsc(IncidentPostmortem::getSubmittedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(review -> items.add(item(review.getId(), "POSTMORTEM_REVIEW", "复盘待评审",
                        review.getReviewNo(), review.getTitle(), firstText(review.getExecutiveSummary(), review.getRootCause()),
                        "HIGH", review.getStatus().name(), "postmortems", "评审复盘", true,
                        review.getSubmittedAt(), review.getUpdatedAt())));
    }

    private void appendKnowledgeReviews(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        knowledgeMapper.selectList(new LambdaQueryWrapper<KnowledgeArticle>()
                        .eq(KnowledgeArticle::getTenantId, tenantId)
                        .eq(KnowledgeArticle::getStatus, KnowledgeStatus.PENDING_REVIEW)
                        .orderByAsc(KnowledgeArticle::getReviewRequestedAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(article -> items.add(item(article.getId(), "KNOWLEDGE_REVIEW", "知识待审核",
                        article.getSlug(), article.getTitle(), "质量门禁已通过，等待管理员审核发布",
                        "HIGH", article.getStatus().name(), "knowledge", "审核文章", true,
                        article.getReviewRequestedAt(), article.getUpdatedAt())));
    }

    private void appendOverdueKnowledgeReviews(List<OperationsWorkbenchView.WorkItem> items, Long tenantId) {
        knowledgeMapper.selectList(new LambdaQueryWrapper<KnowledgeArticle>()
                        .eq(KnowledgeArticle::getTenantId, tenantId)
                        .eq(KnowledgeArticle::getStatus, KnowledgeStatus.PUBLISHED)
                        .isNotNull(KnowledgeArticle::getReviewDueAt)
                        .le(KnowledgeArticle::getReviewDueAt, LocalDateTime.now())
                        .orderByAsc(KnowledgeArticle::getReviewDueAt)
                        .last("LIMIT " + SOURCE_LIMIT))
                .forEach(article -> items.add(item(article.getId(), "KNOWLEDGE_REVIEW_OVERDUE", "知识到期复核",
                        article.getSlug(), article.getTitle(), "文章已超过 180 天复核周期，请确认内容仍然有效",
                        "HIGH", article.getStatus().name(), "knowledge", "复核文章", true,
                        article.getReviewDueAt(), article.getUpdatedAt())));
    }

    private OperationsWorkbenchView.Summary summary(List<OperationsWorkbenchView.WorkItem> items) {
        return new OperationsWorkbenchView.Summary(items.size(), countPriority(items, "CRITICAL"),
                countPriority(items, "HIGH"), countTypes(items, "SERVICE_APPROVAL", "CHANGE_APPROVAL", "POSTMORTEM_REVIEW", "KNOWLEDGE_REVIEW"),
                countTypes(items, "FIRING_ALERT"), countTypes(items, "ACTIVE_INCIDENT"),
                countTypes(items, "SLO_BREACH"), countTypes(items, "STATUS_DRAFT"),
                countTypes(items, "POSTMORTEM_REVIEW"), countTypes(items, "KNOWLEDGE_REVIEW"),
                countTypes(items, "KNOWLEDGE_REVIEW_OVERDUE"));
    }

    private long countPriority(List<OperationsWorkbenchView.WorkItem> items, String priority) {
        return items.stream().filter(item -> priority.equals(item.priority())).count();
    }

    private long countTypes(List<OperationsWorkbenchView.WorkItem> items, String... types) {
        Set<String> expected = Set.of(types);
        return items.stream().filter(item -> expected.contains(item.type())).count();
    }

    private OperationsWorkbenchView.WorkItem item(Long id, String type, String typeLabel, String referenceNo,
                                                   String title, String description, String priority, String status,
                                                   String targetView, String actionLabel, boolean requiresAdmin,
                                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new OperationsWorkbenchView.WorkItem(id, type, typeLabel, referenceNo, title,
                shorten(description), priority, status, targetView, actionLabel, requiresAdmin, createdAt, updatedAt);
    }

    private String ticketPriority(TicketPriority priority) {
        if (priority == null) return "MEDIUM";
        return switch (priority) {
            case URGENT -> "CRITICAL";
            case HIGH -> "HIGH";
            case MEDIUM -> "MEDIUM";
            case LOW -> "LOW";
        };
    }

    private String changeRisk(ChangeRisk risk) {
        if (risk == null) return "MEDIUM";
        return switch (risk) {
            case CRITICAL -> "CRITICAL";
            case HIGH -> "HIGH";
            case MEDIUM -> "MEDIUM";
            case LOW -> "LOW";
        };
    }

    private String alertSeverity(AlertSeverity severity) {
        if (severity == null) return "MEDIUM";
        return switch (severity) {
            case CRITICAL -> "CRITICAL";
            case HIGH -> "HIGH";
            case MEDIUM -> "MEDIUM";
            case LOW, INFO -> "LOW";
        };
    }

    private String incidentSeverity(IncidentSeverity severity) {
        if (severity == null) return "MEDIUM";
        return switch (severity) {
            case SEV1 -> "CRITICAL";
            case SEV2 -> "HIGH";
            case SEV3 -> "MEDIUM";
            case SEV4 -> "LOW";
        };
    }

    private String statusImpact(StatusPageIncidentImpact impact) {
        if (impact == null) return "MEDIUM";
        return switch (impact) {
            case CRITICAL -> "CRITICAL";
            case MAJOR -> "HIGH";
            case MINOR -> "MEDIUM";
        };
    }

    private int priorityRank(String priority) {
        return switch (priority) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    private Set<String> csvFilter(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String breachDescription(SloBreachEvent breach) {
        return "窗口可用性 " + decimal(breach.getAvailabilityPercent())
                + "% · 错误预算剩余 " + decimal(breach.getBudgetRemainingPercent()) + "%";
    }

    private String decimal(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private String shorten(String value) {
        if (value == null || value.isBlank()) return "暂无补充说明";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 177) + "...";
    }
}
