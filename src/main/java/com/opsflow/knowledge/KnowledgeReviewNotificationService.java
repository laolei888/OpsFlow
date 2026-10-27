package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.messaging.AsyncNotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeReviewNotificationService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReviewNotificationService.class);

    private final KnowledgeMapper knowledgeMapper;
    private final AuthService authService;
    private final AsyncNotificationPublisher notificationPublisher;

    public int notifyReviewRequested(KnowledgeArticle article, Long actorId) {
        int queued = 0;
        for (StaffSummary admin : admins(article.getTenantId())) {
            if (Objects.equals(admin.id(), actorId)) continue;
            if (notificationPublisher.enqueue(article.getTenantId(), admin.id(), null,
                    "KNOWLEDGE_REVIEW_REQUESTED", headline("知识文章待审核 · ", article.getTitle()),
                    content("「" + article.getTitle() + "」已完成质量检查，请进入知识治理队列审核。"),
                    "KNOWLEDGE_REVIEW_REQUESTED:" + article.getId() + ":" + article.getReviewRequestedAt(),
                    "KNOWLEDGE_ARTICLE", article.getId())) queued++;
        }
        return queued;
    }

    public boolean notifyReviewDecision(KnowledgeArticle article, Long requesterId,
                                        KnowledgeReviewDecision decision) {
        if (requesterId == null) return false;
        boolean approved = decision == KnowledgeReviewDecision.APPROVE;
        String type = approved ? "KNOWLEDGE_REVIEW_APPROVED" : "KNOWLEDGE_REVIEW_REJECTED";
        String result = approved ? "已审核通过并正式发布。" : "未通过审核，已退回草稿。";
        String comment = article.getReviewComment() == null ? "" : " 审核意见：" + article.getReviewComment();
        return notificationPublisher.enqueue(article.getTenantId(), requesterId, null,
                type, headline(approved ? "知识文章已发布 · " : "知识审核已驳回 · ", article.getTitle()),
                content("「" + article.getTitle() + "」" + result + comment),
                type + ":" + article.getId() + ":" + article.getReviewedAt(),
                "KNOWLEDGE_ARTICLE", article.getId());
    }

    public boolean notifyReviewWithdrawn(KnowledgeArticle article, Long requesterId, Long actorId) {
        if (requesterId == null || Objects.equals(requesterId, actorId)) return false;
        return notificationPublisher.enqueue(article.getTenantId(), requesterId, null,
                "KNOWLEDGE_REVIEW_WITHDRAWN", headline("知识审核已撤回 · ", article.getTitle()),
                content("「" + article.getTitle() + "」已由管理员撤回为草稿，可修改后重新提交。"),
                "KNOWLEDGE_REVIEW_WITHDRAWN:" + article.getId() + ":" + article.getUpdatedAt(),
                "KNOWLEDGE_ARTICLE", article.getId());
    }

    @Transactional
    public int scanDueReviews() {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeArticle> dueArticles = knowledgeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeArticle>()
                        .eq(KnowledgeArticle::getStatus, KnowledgeStatus.PUBLISHED)
                        .isNotNull(KnowledgeArticle::getReviewDueAt)
                        .le(KnowledgeArticle::getReviewDueAt, now)
                        .orderByAsc(KnowledgeArticle::getReviewDueAt));
        Map<Long, List<StaffSummary>> adminsByTenant = new HashMap<>();
        int queued = 0;
        for (KnowledgeArticle article : dueArticles) {
            if (article.getTenantId() == null) continue;
            List<StaffSummary> admins = adminsByTenant.computeIfAbsent(article.getTenantId(), this::admins);
            for (StaffSummary admin : admins) {
                if (notificationPublisher.enqueue(article.getTenantId(), admin.id(), null,
                        "KNOWLEDGE_REVIEW_DUE", headline("知识文章到期复核 · ", article.getTitle()),
                        content("「" + article.getTitle() + "」已到 180 天复核周期，请确认内容仍然准确、可复现。"),
                        "KNOWLEDGE_REVIEW_DUE:" + article.getId() + ":" + article.getReviewDueAt(),
                        "KNOWLEDGE_ARTICLE", article.getId())) queued++;
            }
        }
        return queued;
    }

    @Scheduled(
            fixedDelayString = "${opsflow.knowledge.review-scan-interval-ms:3600000}",
            initialDelayString = "${opsflow.knowledge.review-scan-initial-delay-ms:45000}")
    public void scheduledScan() {
        try {
            int queued = scanDueReviews();
            if (queued > 0) log.info("Knowledge review scan queued {} asynchronous notification(s)", queued);
        } catch (Exception ex) {
            log.error("Knowledge review notification scan failed", ex);
        }
    }

    private List<StaffSummary> admins(Long tenantId) {
        return authService.staffMembers(tenantId).stream()
                .filter(staff -> staff.roles().contains("ADMIN"))
                .toList();
    }

    private String headline(String prefix, String title) {
        String value = prefix + title;
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }

    private String content(String value) {
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }
}
