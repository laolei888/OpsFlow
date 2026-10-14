package com.opsflow.satisfaction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.common.BusinessException;
import com.opsflow.supportgroup.SupportGroup;
import com.opsflow.supportgroup.SupportGroupMapper;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketSatisfactionService {
    private static final Set<TicketStatus> REVIEWABLE_STATUSES =
            EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    private final TicketSatisfactionResponseMapper responseMapper;
    private final TicketMapper ticketMapper;
    private final SupportGroupMapper supportGroupMapper;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<SurveyItem> mySurveys(Long requesterId, Long tenantId, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 100));
        List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .eq(Ticket::getRequesterId, requesterId)
                .in(Ticket::getStatus, REVIEWABLE_STATUSES)
                .orderByDesc(Ticket::getUpdatedAt)
                .orderByDesc(Ticket::getId)
                .last("LIMIT " + safeLimit));
        if (tickets.isEmpty()) return List.of();

        Map<Long, TicketSatisfactionResponse> responses = responseMapper.selectList(
                        new LambdaQueryWrapper<TicketSatisfactionResponse>()
                                .eq(TicketSatisfactionResponse::getTenantId, tenantId)
                                .in(TicketSatisfactionResponse::getTicketId,
                                        tickets.stream().map(Ticket::getId).toList()))
                .stream()
                .collect(Collectors.toMap(TicketSatisfactionResponse::getTicketId, Function.identity()));
        return tickets.stream().map(ticket -> toSurveyItem(ticket, responses.get(ticket.getId()))).toList();
    }

    @Transactional
    public SurveyItem submit(Long ticketId, Integer rating, List<String> rawTags, String rawComment,
                             Long requesterId, Long tenantId) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException("满意度评分必须在 1-5 分之间");
        }
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getId, ticketId)
                .eq(Ticket::getTenantId, tenantId));
        if (ticket == null) throw new BusinessException("工单不存在");
        if (!requesterId.equals(ticket.getRequesterId())) {
            throw new BusinessException("只有工单申请人可以提交服务评价");
        }
        if (!REVIEWABLE_STATUSES.contains(ticket.getStatus())) {
            throw new BusinessException("工单解决后才可以提交服务评价");
        }
        if (responseMapper.selectCount(new LambdaQueryWrapper<TicketSatisfactionResponse>()
                .eq(TicketSatisfactionResponse::getTenantId, tenantId)
                .eq(TicketSatisfactionResponse::getTicketId, ticketId)) > 0) {
            throw new BusinessException("该工单已经提交过服务评价");
        }

        List<String> tags = normalizeTags(rawTags);
        String comment = normalizeComment(rawComment);
        TicketSatisfactionResponse response = new TicketSatisfactionResponse();
        response.setTenantId(tenantId);
        response.setTicketId(ticketId);
        response.setRequesterId(requesterId);
        response.setAssigneeId(ticket.getAssigneeId());
        response.setAssignmentGroupId(ticket.getAssignmentGroupId());
        response.setRating(rating);
        response.setTags(tags.isEmpty() ? null : String.join(",", tags));
        response.setComment(comment);
        response.setSubmittedAt(LocalDateTime.now());
        try {
            responseMapper.insert(response);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("该工单已经提交过服务评价");
        }
        auditLogService.record(tenantId, requesterId, "TICKET", ticketId, "SATISFACTION_SUBMITTED",
                null, Map.of("rating", rating, "tags", tags), "申请人提交服务满意度评价");
        return toSurveyItem(ticket, response);
    }

    @Transactional(readOnly = true)
    public AnalyticsSummary analytics(Long tenantId, Integer days, Integer recentLimit) {
        int safeDays = days == null ? 30 : Math.max(1, Math.min(days, 365));
        int safeLimit = recentLimit == null ? 30 : Math.max(1, Math.min(recentLimit, 100));
        LocalDateTime from = LocalDateTime.now().minusDays(safeDays);
        SatisfactionAggregateRow aggregate = responseMapper.summarize(tenantId, from);
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int score = 5; score >= 1; score--) distribution.put(score, 0L);
        responseMapper.distribution(tenantId, from).forEach(row ->
                distribution.put(row.getScore(), row.getResponses()));
        long total = aggregate == null || aggregate.getTotalResponses() == null
                ? 0 : aggregate.getTotalResponses();
        long positive = aggregate == null || aggregate.getPositiveCount() == null
                ? 0 : aggregate.getPositiveCount();
        long low = aggregate == null || aggregate.getLowRatingCount() == null
                ? 0 : aggregate.getLowRatingCount();
        double average = aggregate == null || aggregate.getAverageRating() == null
                ? 0 : aggregate.getAverageRating().setScale(2, RoundingMode.HALF_UP).doubleValue();

        Map<Long, String> groupNames = supportGroupMapper.selectList(new LambdaQueryWrapper<SupportGroup>()
                        .eq(SupportGroup::getTenantId, tenantId))
                .stream().collect(Collectors.toMap(SupportGroup::getId, SupportGroup::getName));
        Map<Long, String> staffNames = authService.staffMembers(tenantId).stream()
                .collect(Collectors.toMap(StaffSummary::id, StaffSummary::displayName));

        List<DimensionMetric> groups = dimensionMetrics(
                responseMapper.groupMetrics(tenantId, from), groupNames, "未分组", "历史支持组");
        List<DimensionMetric> assignees = dimensionMetrics(
                responseMapper.assigneeMetrics(tenantId, from), staffNames, "未指派", "历史成员");

        List<TicketSatisfactionResponse> recentResponses = responseMapper.recent(tenantId, from, safeLimit);
        Map<Long, Ticket> tickets = recentResponses.isEmpty() ? Map.of() : ticketMapper.selectList(
                        new LambdaQueryWrapper<Ticket>()
                                .eq(Ticket::getTenantId, tenantId)
                                .in(Ticket::getId, recentResponses.stream()
                                        .map(TicketSatisfactionResponse::getTicketId).toList()))
                .stream().collect(Collectors.toMap(Ticket::getId, Function.identity()));
        List<ResponseView> recent = recentResponses.stream()
                .map(response -> toResponseView(response, tickets.get(response.getTicketId()),
                        staffNames, groupNames))
                .toList();

        return new AnalyticsSummary(safeDays, total, average, positive,
                percent(positive, total), low, distribution, groups, assignees, recent);
    }

    private List<DimensionMetric> dimensionMetrics(List<SatisfactionDimensionRow> rows,
                                                    Map<Long, String> names, String emptyName,
                                                    String historicalName) {
        if (rows == null) return List.of();
        return rows.stream().map(row -> {
            long responses = row.getResponses() == null ? 0 : row.getResponses();
            long positive = row.getPositiveCount() == null ? 0 : row.getPositiveCount();
            double average = row.getAverageRating() == null ? 0
                    : row.getAverageRating().setScale(2, RoundingMode.HALF_UP).doubleValue();
            return new DimensionMetric(row.getDimensionId(), row.getDimensionId() == null ? emptyName
                    : names.getOrDefault(row.getDimensionId(), historicalName),
                    responses, average, percent(positive, responses));
        }).toList();
    }

    private SurveyItem toSurveyItem(Ticket ticket, TicketSatisfactionResponse response) {
        return new SurveyItem(ticket.getId(), ticket.getTicketNo(), ticket.getTitle(), ticket.getStatus(),
                ticket.getAssigneeId(), ticket.getAssignmentGroupId(), ticket.getUpdatedAt(),
                response == null ? null : response.getRating(),
                response == null ? List.of() : splitTags(response.getTags()),
                response == null ? null : response.getComment(),
                response == null ? null : response.getSubmittedAt(), response == null);
    }

    private ResponseView toResponseView(TicketSatisfactionResponse response, Ticket ticket,
                                        Map<Long, String> staffNames, Map<Long, String> groupNames) {
        return new ResponseView(response.getId(), response.getTicketId(),
                ticket == null ? "#" + response.getTicketId() : ticket.getTicketNo(),
                ticket == null ? "历史工单" : ticket.getTitle(), response.getRating(),
                splitTags(response.getTags()), response.getComment(), response.getSubmittedAt(),
                response.getAssigneeId(), response.getAssigneeId() == null ? "未指派"
                        : staffNames.getOrDefault(response.getAssigneeId(), "历史成员"),
                response.getAssignmentGroupId(), response.getAssignmentGroupId() == null ? "未分组"
                        : groupNames.getOrDefault(response.getAssignmentGroupId(), "历史支持组"));
    }

    private List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) return List.of();
        Set<String> tags = new LinkedHashSet<>();
        for (String rawTag : rawTags) {
            if (rawTag == null || rawTag.isBlank()) continue;
            String normalized = rawTag.trim().toUpperCase();
            try {
                tags.add(SatisfactionTag.valueOf(normalized).name());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("服务评价标签不合法: " + rawTag);
            }
        }
        if (tags.size() > 5) throw new BusinessException("单次评价最多选择 5 个标签");
        return new ArrayList<>(tags);
    }

    private String normalizeComment(String rawComment) {
        if (rawComment == null || rawComment.isBlank()) return null;
        String comment = rawComment.trim();
        if (comment.length() > 2000) throw new BusinessException("评价内容不能超过 2000 个字符");
        return comment;
    }

    private List<String> splitTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split(","));
    }

    private double percent(long numerator, long denominator) {
        if (denominator == 0) return 0;
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    public record SurveyItem(Long ticketId, String ticketNo, String title, TicketStatus status,
                             Long assigneeId, Long assignmentGroupId, LocalDateTime resolvedAt,
                             Integer rating, List<String> tags, String comment,
                             LocalDateTime submittedAt, boolean eligible) { }

    public record DimensionMetric(Long id, String name, long responses,
                                  double averageRating, double positiveRate) { }

    public record ResponseView(Long id, Long ticketId, String ticketNo, String ticketTitle,
                               int rating, List<String> tags, String comment, LocalDateTime submittedAt,
                               Long assigneeId, String assigneeName,
                               Long assignmentGroupId, String assignmentGroupName) { }

    public record AnalyticsSummary(int days, long totalResponses, double averageRating,
                                   long positiveCount, double positiveRate, long lowRatingCount,
                                   Map<Integer, Long> distribution, List<DimensionMetric> groups,
                                   List<DimensionMetric> assignees, List<ResponseView> recent) { }
}
