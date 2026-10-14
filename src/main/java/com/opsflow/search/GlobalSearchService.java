package com.opsflow.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.cmdb.CmdbAsset;
import com.opsflow.cmdb.CmdbAssetMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.knowledge.KnowledgeArticle;
import com.opsflow.knowledge.KnowledgeMapper;
import com.opsflow.knowledge.KnowledgeStatus;
import com.opsflow.problem.Problem;
import com.opsflow.problem.ProblemMapper;
import com.opsflow.postmortem.IncidentPostmortem;
import com.opsflow.postmortem.IncidentPostmortemMapper;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GlobalSearchService {
    private static final int PER_DOMAIN_LIMIT = 20;

    private final TicketMapper ticketMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final CmdbAssetMapper assetMapper;
    private final ChangeRequestMapper changeMapper;
    private final ProblemMapper problemMapper;
    private final MonitoringAlertMapper alertMapper;
    private final IncidentMapper incidentMapper;
    private final IncidentPostmortemMapper postmortemMapper;

    public GlobalSearchView search(Long tenantId, Long userId, boolean staff, String rawQuery, Integer requestedLimit) {
        String query = normalizeQuery(rawQuery);
        int limit = Math.max(1, Math.min(requestedLimit == null ? 30 : requestedLimit, 50));
        List<GlobalSearchView.Result> matches = new ArrayList<>();
        searchTickets(matches, tenantId, staff ? null : userId, query);
        searchKnowledge(matches, tenantId, staff, query);
        if (staff) {
            searchAssets(matches, tenantId, query);
            searchChanges(matches, tenantId, query);
            searchProblems(matches, tenantId, query);
            searchAlerts(matches, tenantId, query);
            searchIncidents(matches, tenantId, query);
            searchPostmortems(matches, tenantId, query);
        }
        matches.sort(Comparator.comparingInt(GlobalSearchView.Result::score).reversed()
                .thenComparing(GlobalSearchView.Result::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(GlobalSearchView.Result::id));
        List<GlobalSearchView.Group> groups = groups(matches);
        return new GlobalSearchView(query, matches.size(), groups, matches.stream().limit(limit).toList());
    }

    private void searchTickets(List<GlobalSearchView.Result> results, Long tenantId, Long requesterId, String query) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .eq(requesterId != null, Ticket::getRequesterId, requesterId)
                .and(item -> item.like(Ticket::getTicketNo, query)
                        .or().like(Ticket::getTitle, query)
                        .or().like(Ticket::getDescription, query)
                        .or().like(Ticket::getCategory, query))
                .orderByDesc(Ticket::getUpdatedAt)
                .last("LIMIT " + PER_DOMAIN_LIMIT);
        ticketMapper.selectList(wrapper).forEach(ticket -> results.add(result(ticket.getId(), "TICKET", "工单",
                ticket.getTicketNo(), ticket.getTitle(), join(ticket.getCategory(), ticket.getDescription()),
                name(ticket.getStatus()), "tickets", ticket.getUpdatedAt(), query)));
    }

    private void searchKnowledge(List<GlobalSearchView.Result> results, Long tenantId, boolean staff, String query) {
        LambdaQueryWrapper<KnowledgeArticle> wrapper = new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .eq(!staff, KnowledgeArticle::getStatus, KnowledgeStatus.PUBLISHED)
                .and(item -> item.like(KnowledgeArticle::getTitle, query)
                        .or().like(KnowledgeArticle::getSlug, query)
                        .or().like(KnowledgeArticle::getCategory, query)
                        .or().like(KnowledgeArticle::getContent, query))
                .orderByDesc(KnowledgeArticle::getUpdatedAt)
                .last("LIMIT " + PER_DOMAIN_LIMIT);
        knowledgeMapper.selectList(wrapper).forEach(article -> results.add(result(article.getId(), "KNOWLEDGE", "知识",
                article.getSlug(), article.getTitle(), join(article.getCategory(), article.getContent()),
                name(article.getStatus()), "knowledge", article.getUpdatedAt(), query)));
    }

    private void searchAssets(List<GlobalSearchView.Result> results, Long tenantId, String query) {
        assetMapper.selectList(new LambdaQueryWrapper<CmdbAsset>()
                        .eq(CmdbAsset::getTenantId, tenantId)
                        .and(item -> item.like(CmdbAsset::getAssetNo, query)
                                .or().like(CmdbAsset::getName, query)
                                .or().like(CmdbAsset::getBusinessSystem, query)
                                .or().like(CmdbAsset::getLocation, query)
                                .or().like(CmdbAsset::getDescription, query))
                        .orderByDesc(CmdbAsset::getUpdatedAt)
                        .last("LIMIT " + PER_DOMAIN_LIMIT))
                .forEach(asset -> results.add(result(asset.getId(), "CMDB_ASSET", "配置项", asset.getAssetNo(),
                        asset.getName(), join(asset.getBusinessSystem(), asset.getLocation(), asset.getDescription()),
                        name(asset.getStatus()), "cmdb", asset.getUpdatedAt(), query)));
    }

    private void searchChanges(List<GlobalSearchView.Result> results, Long tenantId, String query) {
        changeMapper.selectList(new LambdaQueryWrapper<ChangeRequest>()
                        .eq(ChangeRequest::getTenantId, tenantId)
                        .and(item -> item.like(ChangeRequest::getChangeNo, query)
                                .or().like(ChangeRequest::getTitle, query)
                                .or().like(ChangeRequest::getDescription, query)
                                .or().like(ChangeRequest::getImpactScope, query))
                        .orderByDesc(ChangeRequest::getUpdatedAt)
                        .last("LIMIT " + PER_DOMAIN_LIMIT))
                .forEach(change -> results.add(result(change.getId(), "CHANGE", "变更", change.getChangeNo(),
                        change.getTitle(), join(change.getImpactScope(), change.getDescription()), name(change.getStatus()),
                        "changes", change.getUpdatedAt(), query)));
    }

    private void searchProblems(List<GlobalSearchView.Result> results, Long tenantId, String query) {
        problemMapper.selectList(new LambdaQueryWrapper<Problem>()
                        .eq(Problem::getTenantId, tenantId)
                        .and(item -> item.like(Problem::getProblemNo, query)
                                .or().like(Problem::getTitle, query)
                                .or().like(Problem::getDescription, query)
                                .or().like(Problem::getRootCause, query)
                                .or().like(Problem::getWorkaround, query))
                        .orderByDesc(Problem::getUpdatedAt)
                        .last("LIMIT " + PER_DOMAIN_LIMIT))
                .forEach(problem -> results.add(result(problem.getId(), "PROBLEM", "问题", problem.getProblemNo(),
                        problem.getTitle(), join(problem.getRootCause(), problem.getWorkaround(), problem.getDescription()),
                        name(problem.getStatus()), "problems", problem.getUpdatedAt(), query)));
    }

    private void searchAlerts(List<GlobalSearchView.Result> results, Long tenantId, String query) {
        alertMapper.selectList(new LambdaQueryWrapper<MonitoringAlert>()
                        .eq(MonitoringAlert::getTenantId, tenantId)
                        .and(item -> item.like(MonitoringAlert::getAlertNo, query)
                                .or().like(MonitoringAlert::getExternalAlertId, query)
                                .or().like(MonitoringAlert::getTitle, query)
                                .or().like(MonitoringAlert::getDescription, query)
                                .or().like(MonitoringAlert::getLabelsJson, query))
                        .orderByDesc(MonitoringAlert::getUpdatedAt)
                        .last("LIMIT " + PER_DOMAIN_LIMIT))
                .forEach(alert -> results.add(result(alert.getId(), "ALERT", "告警", alert.getAlertNo(),
                        alert.getTitle(), alert.getDescription(), name(alert.getStatus()), "alerts",
                        alert.getUpdatedAt(), query)));
    }

    private void searchIncidents(List<GlobalSearchView.Result> results, Long tenantId, String query) {
        incidentMapper.selectList(new LambdaQueryWrapper<Incident>()
                        .eq(Incident::getTenantId, tenantId)
                        .and(item -> item.like(Incident::getIncidentNo, query)
                                .or().like(Incident::getTitle, query)
                                .or().like(Incident::getSummary, query)
                                .or().like(Incident::getBusinessImpact, query)
                                .or().like(Incident::getCustomerImpact, query))
                        .orderByDesc(Incident::getUpdatedAt)
                        .last("LIMIT " + PER_DOMAIN_LIMIT))
                .forEach(incident -> results.add(result(incident.getId(), "INCIDENT", "重大事件",
                        incident.getIncidentNo(), incident.getTitle(),
                        join(incident.getBusinessImpact(), incident.getCustomerImpact(), incident.getSummary()),
                        name(incident.getStatus()), "incidents", incident.getUpdatedAt(), query)));
    }

    private void searchPostmortems(List<GlobalSearchView.Result> results, Long tenantId, String query) {
        postmortemMapper.selectList(new LambdaQueryWrapper<IncidentPostmortem>()
                        .eq(IncidentPostmortem::getTenantId, tenantId)
                        .and(item -> item.like(IncidentPostmortem::getReviewNo, query)
                                .or().like(IncidentPostmortem::getTitle, query)
                                .or().like(IncidentPostmortem::getExecutiveSummary, query)
                                .or().like(IncidentPostmortem::getRootCause, query)
                                .or().like(IncidentPostmortem::getLessonsLearned, query)
                                .or().like(IncidentPostmortem::getPreventionPlan, query))
                        .orderByDesc(IncidentPostmortem::getUpdatedAt)
                        .last("LIMIT " + PER_DOMAIN_LIMIT))
                .forEach(review -> results.add(result(review.getId(), "POSTMORTEM", "事件复盘",
                        review.getReviewNo(), review.getTitle(),
                        join(review.getExecutiveSummary(), review.getRootCause(), review.getLessonsLearned(),
                                review.getPreventionPlan()),
                        name(review.getStatus()), "postmortems", review.getUpdatedAt(), query)));
    }

    private GlobalSearchView.Result result(Long id, String type, String typeLabel, String referenceNo,
                                           String title, String body, String status, String targetView,
                                           LocalDateTime updatedAt, String query) {
        return new GlobalSearchView.Result(id, type, typeLabel, referenceNo, title,
                snippet(body, query), status, targetView, updatedAt,
                score(query, referenceNo, title, body, updatedAt));
    }

    private List<GlobalSearchView.Group> groups(List<GlobalSearchView.Result> results) {
        Map<String, GlobalSearchView.Group> groups = new LinkedHashMap<>();
        for (GlobalSearchView.Result result : results) {
            GlobalSearchView.Group current = groups.get(result.type());
            groups.put(result.type(), new GlobalSearchView.Group(result.type(), result.typeLabel(),
                    current == null ? 1 : current.count() + 1));
        }
        return List.copyOf(groups.values());
    }

    private int score(String query, String referenceNo, String title, String body, LocalDateTime updatedAt) {
        String expected = lower(query);
        String reference = lower(referenceNo);
        String heading = lower(title);
        String content = lower(body);
        int score = 10;
        if (reference.equals(expected)) score += 120;
        else if (reference.startsWith(expected)) score += 95;
        else if (reference.contains(expected)) score += 75;
        if (heading.equals(expected)) score += 100;
        else if (heading.startsWith(expected)) score += 80;
        else if (heading.contains(expected)) score += 60;
        if (content.contains(expected)) score += 25;
        if (updatedAt != null) {
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(updatedAt, LocalDateTime.now()));
            score += (int) Math.max(0, 10 - ageDays);
        }
        return score;
    }

    private String snippet(String value, String query) {
        if (value == null || value.isBlank()) return "暂无摘要";
        String normalized = value.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        int match = lower.indexOf(query.toLowerCase(Locale.ROOT));
        int start = match < 0 ? 0 : Math.max(0, match - 50);
        int end = Math.min(normalized.length(), start + 150);
        return (start > 0 ? "..." : "") + normalized.substring(start, end) + (end < normalized.length() ? "..." : "");
    }

    private String normalizeQuery(String query) {
        if (query == null || query.trim().length() < 2) throw new BusinessException("搜索关键词至少需要 2 个字符");
        String normalized = query.trim();
        if (normalized.length() > 100) throw new BusinessException("搜索关键词不能超过 100 个字符");
        return normalized;
    }

    private String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!result.isEmpty()) result.append(" · ");
            result.append(value.trim());
        }
        return result.toString();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String name(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }
}
