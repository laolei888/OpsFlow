package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeService {
    private static final int TITLE_MAX_LENGTH = 160;
    private static final int SLUG_MAX_LENGTH = 180;
    private static final int CATEGORY_MAX_LENGTH = 64;
    private static final int CONTENT_MAX_LENGTH = 50000;
    private static final int REVIEW_COMMENT_MAX_LENGTH = 1000;
    private static final int MIN_REVIEW_CONTENT_LENGTH = 80;
    private static final int REVIEW_VALID_DAYS = 180;

    private final KnowledgeMapper knowledgeMapper;
    private final AuditLogService auditLogService;
    private final KnowledgeArticleVersionService articleVersionService;
    private final KnowledgeReviewNotificationService reviewNotificationService;

    public Page<KnowledgeArticle> search(long page, long size, String keyword, String category, Long tenantId) {
        return search(page, size, keyword, category, tenantId, false);
    }

    public Page<KnowledgeArticle> search(long page, long size, String keyword, String category,
                                         Long tenantId, boolean staff) {
        LambdaQueryWrapper<KnowledgeArticle> query = new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .orderByDesc(KnowledgeArticle::getUpdatedAt)
                .orderByDesc(KnowledgeArticle::getId);
        if (!staff) query.eq(KnowledgeArticle::getStatus, KnowledgeStatus.PUBLISHED);
        if (category != null && !category.isBlank()) {
            query.eq(KnowledgeArticle::getCategory, category.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(KnowledgeArticle::getTitle, search)
                    .or().like(KnowledgeArticle::getContent, search));
        }
        return knowledgeMapper.selectPage(new Page<>(page, Math.min(size, 50)), query);
    }

    public KnowledgeArticle getPublished(Long id, Long tenantId) {
        KnowledgeArticle article = knowledgeMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getId, id)
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .eq(KnowledgeArticle::getStatus, KnowledgeStatus.PUBLISHED));
        if (article == null) throw new BusinessException("知识库文章不存在");
        article.setViewCount((article.getViewCount() == null ? 0L : article.getViewCount()) + 1);
        knowledgeMapper.updateById(article);
        return article;
    }

    @Transactional
    public KnowledgeArticle create(CreateArticleCommand command, Long authorId, Long tenantId) {
        String title = requiredText(command.title(), "标题", TITLE_MAX_LENGTH);
        String slug = requiredText(command.slug(), "Slug", SLUG_MAX_LENGTH);
        String category = requiredText(command.category(), "分类", CATEGORY_MAX_LENGTH);
        String content = requiredText(command.content(), "内容", CONTENT_MAX_LENGTH);
        ensureSlugAvailable(tenantId, slug, null);

        KnowledgeArticle article = new KnowledgeArticle();
        article.setTenantId(tenantId);
        article.setAuthorId(authorId);
        article.setTitle(title);
        article.setSlug(slug);
        article.setCategory(category);
        article.setContent(content);
        article.setStatus(KnowledgeStatus.DRAFT);
        initializeReviewFields(article);
        article.setVersion(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(article.getCreatedAt());
        try {
            knowledgeMapper.insert(article);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("文章 Slug 已存在，请换一个");
        }
        articleVersionService.record(article, authorId, "CREATED");
        auditLogService.record(tenantId, authorId, "KNOWLEDGE_ARTICLE", article.getId(),
                "KNOWLEDGE_ARTICLE_CREATED", null, snapshot(article), "创建知识文章");
        return article;
    }

    public KnowledgeArticle findBySourceTicket(Long sourceTicketId, Long tenantId) {
        if (sourceTicketId == null) return null;
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .eq(KnowledgeArticle::getSourceTicketId, sourceTicketId));
    }

    @Transactional
    public KnowledgeDraftResult createFromResolvedTicket(Ticket ticket, Long authorId, Long tenantId) {
        if (ticket == null || ticket.getId() == null || !tenantId.equals(ticket.getTenantId())) {
            throw new BusinessException("来源工单不存在");
        }
        if ((ticket.getStatus() != TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.CLOSED)
                || ticket.getConfirmedAt() == null) {
            throw new BusinessException("只有申请人已经确认解决的工单才能沉淀知识");
        }
        String resolutionSummary = requiredText(ticket.getResolutionSummary(), "解决摘要", 10000);
        String verificationResult = requiredText(ticket.getVerificationResult(), "验证结果", 2000);
        KnowledgeArticle existing = findBySourceTicket(ticket.getId(), tenantId);
        if (existing != null) return new KnowledgeDraftResult(existing, false);

        KnowledgeArticle article = new KnowledgeArticle();
        article.setTenantId(tenantId);
        article.setAuthorId(authorId);
        article.setSourceTicketId(ticket.getId());
        article.setSourceTicketNo(ticket.getTicketNo());
        article.setTitle(sourceTitle(ticket));
        article.setSlug(sourceSlug(ticket));
        article.setCategory(ticket.getCategory() == null || ticket.getCategory().isBlank()
                ? "未分类" : ticket.getCategory().trim());
        article.setContent(sourceContent(ticket, resolutionSummary, verificationResult));
        article.setStatus(KnowledgeStatus.DRAFT);
        initializeReviewFields(article);
        article.setVersion(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(article.getCreatedAt());
        try {
            knowledgeMapper.insert(article);
        } catch (DataIntegrityViolationException ex) {
            KnowledgeArticle concurrent = findBySourceTicketForUpdate(ticket.getId(), tenantId);
            if (concurrent != null) return new KnowledgeDraftResult(concurrent, false);
            throw new BusinessException("知识草稿创建冲突，请刷新后重试");
        }
        articleVersionService.record(article, authorId, "CREATED_FROM_TICKET");
        auditLogService.record(tenantId, authorId, "KNOWLEDGE_ARTICLE", article.getId(),
                "KNOWLEDGE_DRAFT_CREATED_FROM_TICKET", null, snapshot(article),
                "从工单 " + ticket.getTicketNo() + " 沉淀知识草稿");
        return new KnowledgeDraftResult(article, true);
    }

    private KnowledgeArticle findBySourceTicketForUpdate(Long sourceTicketId, Long tenantId) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .eq(KnowledgeArticle::getSourceTicketId, sourceTicketId)
                .last("FOR UPDATE"));
    }

    @Transactional
    public KnowledgeArticle update(Long id, UpdateArticleCommand command, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() != KnowledgeStatus.DRAFT) {
            throw new BusinessException("只有草稿可以编辑；审核中或已发布文章需先退回草稿");
        }
        Map<String, Object> before = snapshot(article);
        String title = requiredText(command.title(), "标题", TITLE_MAX_LENGTH);
        String slug = requiredText(command.slug(), "Slug", SLUG_MAX_LENGTH);
        String category = requiredText(command.category(), "分类", CATEGORY_MAX_LENGTH);
        String content = requiredText(command.content(), "内容", CONTENT_MAX_LENGTH);
        ensureSlugAvailable(tenantId, slug, id);

        boolean changed = !Objects.equals(article.getTitle(), title)
                || !Objects.equals(article.getSlug(), slug)
                || !Objects.equals(article.getCategory(), category)
                || !Objects.equals(article.getContent(), content);
        if (!changed) return article;

        article.setTitle(title);
        article.setSlug(slug);
        article.setCategory(category);
        article.setContent(content);
        article.setUpdatedAt(LocalDateTime.now());
        if (knowledgeMapper.updateById(article) == 0) {
            throw new BusinessException("文章已被其他人更新，请刷新后重试");
        }
        articleVersionService.record(article, actorId, "UPDATED");
        auditLogService.record(tenantId, actorId, "KNOWLEDGE_ARTICLE", article.getId(),
                "KNOWLEDGE_ARTICLE_UPDATED", before, snapshot(article), "编辑知识文章");
        return article;
    }

    public KnowledgeArticle publish(Long id, Long tenantId) {
        return publish(id, null, tenantId);
    }

    @Transactional
    public KnowledgeArticle publish(Long id, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() != KnowledgeStatus.PENDING_REVIEW) {
            throw new BusinessException("文章必须先提交审核，不能直接发布");
        }
        return reviewArticle(article, KnowledgeReviewDecision.APPROVE, null, actorId, tenantId);
    }

    @Transactional
    public KnowledgeArticle submitForReview(Long id, ReviewChecklist checklist, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() != KnowledgeStatus.DRAFT) {
            throw new BusinessException("只有草稿可以提交审核");
        }
        if (article.getContent() == null || article.getContent().trim().length() < MIN_REVIEW_CONTENT_LENGTH) {
            throw new BusinessException("文章内容至少需要 " + MIN_REVIEW_CONTENT_LENGTH + " 个字符才能提交审核");
        }
        if (checklist == null || !checklist.sensitiveDataChecked()
                || !checklist.scopeDefined() || !checklist.verificationStepsComplete()) {
            throw new BusinessException("请完成脱敏、适用范围和验证步骤三项质量检查");
        }
        Map<String, Object> before = snapshot(article);
        LocalDateTime now = LocalDateTime.now();
        article.setStatus(KnowledgeStatus.PENDING_REVIEW);
        article.setReviewRequestedBy(actorId);
        article.setReviewRequestedAt(now);
        article.setReviewedBy(null);
        article.setReviewedAt(null);
        article.setReviewComment(null);
        article.setSensitiveDataChecked(true);
        article.setScopeDefined(true);
        article.setVerificationStepsComplete(true);
        article.setReviewDueAt(null);
        article.setUpdatedAt(now);
        updateArticle(article, "文章已被其他人更新，请刷新后重试");
        articleVersionService.record(article, actorId, "SUBMITTED_FOR_REVIEW");
        auditLogService.record(tenantId, actorId, "KNOWLEDGE_ARTICLE", article.getId(),
                "KNOWLEDGE_REVIEW_SUBMITTED", before, snapshot(article), "提交知识文章审核");
        reviewNotificationService.notifyReviewRequested(article, actorId);
        return article;
    }

    @Transactional
    public KnowledgeArticle review(Long id, KnowledgeReviewDecision decision, String comment,
                                   Long reviewerId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        return reviewArticle(article, decision, comment, reviewerId, tenantId);
    }

    @Transactional
    public KnowledgeArticle withdrawReview(Long id, Long actorId, boolean admin, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() != KnowledgeStatus.PENDING_REVIEW) {
            throw new BusinessException("只有审核中的文章可以撤回");
        }
        if (!admin && !Objects.equals(article.getReviewRequestedBy(), actorId)) {
            throw new BusinessException("只有提交审核的人或管理员可以撤回");
        }
        Long requesterId = article.getReviewRequestedBy();
        Map<String, Object> before = snapshot(article);
        article.setStatus(KnowledgeStatus.DRAFT);
        article.setReviewRequestedBy(null);
        article.setReviewRequestedAt(null);
        article.setSensitiveDataChecked(false);
        article.setScopeDefined(false);
        article.setVerificationStepsComplete(false);
        article.setUpdatedAt(LocalDateTime.now());
        updateArticle(article, "文章已被其他人更新，请刷新后重试");
        articleVersionService.record(article, actorId, "REVIEW_WITHDRAWN");
        auditLogService.record(tenantId, actorId, "KNOWLEDGE_ARTICLE", article.getId(),
                "KNOWLEDGE_REVIEW_WITHDRAWN", before, snapshot(article), "撤回知识文章审核");
        reviewNotificationService.notifyReviewWithdrawn(article, requesterId, actorId);
        return article;
    }

    @Transactional
    public KnowledgeArticle archive(Long id, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() == KnowledgeStatus.ARCHIVED) return article;
        return changeStatus(article, KnowledgeStatus.ARCHIVED, actorId, tenantId,
                "KNOWLEDGE_ARTICLE_ARCHIVED", "归档知识文章");
    }

    @Transactional
    public KnowledgeArticle restore(Long id, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() != KnowledgeStatus.ARCHIVED) {
            throw new BusinessException("只有已归档文章可以恢复");
        }
        initializeReviewFields(article);
        return changeStatus(article, KnowledgeStatus.DRAFT, actorId, tenantId,
                "KNOWLEDGE_ARTICLE_RESTORED", "恢复知识文章为草稿");
    }

    private KnowledgeArticle changeStatus(KnowledgeArticle article, KnowledgeStatus target,
                                          Long actorId, Long tenantId, String action, String detail) {
        KnowledgeStatus beforeStatus = article.getStatus();
        article.setStatus(target);
        article.setUpdatedAt(LocalDateTime.now());
        if (knowledgeMapper.updateById(article) == 0) {
            throw new BusinessException("文章已被其他人更新，请刷新后重试");
        }
        articleVersionService.record(article, actorId, action.replace("KNOWLEDGE_ARTICLE_", ""));
        auditLogService.record(tenantId, actorId, "KNOWLEDGE_ARTICLE", article.getId(), action,
                Map.of("status", beforeStatus.name()), Map.of("status", target.name()), detail);
        return article;
    }

    @Transactional
    public KnowledgeArticle restoreVersion(Long id, Long versionId, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
        if (article.getStatus() != KnowledgeStatus.DRAFT) {
            throw new BusinessException("只有草稿可以恢复历史版本");
        }
        KnowledgeArticleVersion version = articleVersionService.getForArticle(id, versionId, tenantId);
        String title = requiredText(version.getTitle(), "标题", TITLE_MAX_LENGTH);
        String slug = requiredText(version.getSlug(), "Slug", SLUG_MAX_LENGTH);
        String category = requiredText(version.getCategory(), "分类", CATEGORY_MAX_LENGTH);
        String content = requiredText(version.getContent(), "内容", CONTENT_MAX_LENGTH);
        ensureSlugAvailable(tenantId, slug, id);

        Map<String, Object> before = snapshot(article);
        boolean changed = !Objects.equals(article.getTitle(), title)
                || !Objects.equals(article.getSlug(), slug)
                || !Objects.equals(article.getCategory(), category)
                || !Objects.equals(article.getContent(), content);
        if (!changed) return article;

        article.setTitle(title);
        article.setSlug(slug);
        article.setCategory(category);
        article.setContent(content);
        article.setUpdatedAt(LocalDateTime.now());
        if (knowledgeMapper.updateById(article) == 0) {
            throw new BusinessException("文章已被其他人更新，请刷新后重试");
        }
        articleVersionService.record(article, actorId, "VERSION_RESTORED");
        auditLogService.record(tenantId, actorId, "KNOWLEDGE_ARTICLE", article.getId(),
                "KNOWLEDGE_ARTICLE_VERSION_RESTORED", before, snapshot(article), "恢复历史版本");
        return article;
    }
    public List<KnowledgeArticleVersion> versions(Long id, Long tenantId) {
        getManageable(id, tenantId);
        return articleVersionService.listForArticle(id, tenantId);
    }
    private KnowledgeArticle getManageable(Long id, Long tenantId) {
        KnowledgeArticle article = knowledgeMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getId, id)
                .eq(KnowledgeArticle::getTenantId, tenantId));
        if (article == null) throw new BusinessException("知识库文章不存在");
        return article;
    }

    private void ensureSlugAvailable(Long tenantId, String slug, Long excludeId) {
        KnowledgeArticle duplicate = knowledgeMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .eq(KnowledgeArticle::getSlug, slug));
        if (duplicate != null && (excludeId == null || !excludeId.equals(duplicate.getId()))) {
            throw new BusinessException("文章 Slug 已存在，请换一个");
        }
    }

    private Map<String, Object> snapshot(KnowledgeArticle article) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", article.getTitle());
        snapshot.put("slug", article.getSlug());
        snapshot.put("category", article.getCategory());
        snapshot.put("content", article.getContent());
        snapshot.put("status", article.getStatus() == null ? null : article.getStatus().name());
        snapshot.put("sourceTicketId", article.getSourceTicketId());
        snapshot.put("sourceTicketNo", article.getSourceTicketNo());
        snapshot.put("reviewRequestedBy", article.getReviewRequestedBy());
        snapshot.put("reviewRequestedAt", article.getReviewRequestedAt());
        snapshot.put("reviewedBy", article.getReviewedBy());
        snapshot.put("reviewedAt", article.getReviewedAt());
        snapshot.put("reviewComment", article.getReviewComment());
        snapshot.put("sensitiveDataChecked", article.getSensitiveDataChecked());
        snapshot.put("scopeDefined", article.getScopeDefined());
        snapshot.put("verificationStepsComplete", article.getVerificationStepsComplete());
        snapshot.put("reviewDueAt", article.getReviewDueAt());
        return snapshot;
    }

    private KnowledgeArticle reviewArticle(KnowledgeArticle article, KnowledgeReviewDecision decision,
                                           String comment, Long reviewerId, Long tenantId) {
        if (article.getStatus() != KnowledgeStatus.PENDING_REVIEW) {
            throw new BusinessException("当前文章不在待审核状态");
        }
        if (decision == null) throw new BusinessException("请选择审核结论");
        String normalizedComment = optionalText(comment, "审核意见", REVIEW_COMMENT_MAX_LENGTH);
        if (decision == KnowledgeReviewDecision.REJECT && normalizedComment == null) {
            throw new BusinessException("驳回审核时必须填写原因");
        }
        Long requesterId = article.getReviewRequestedBy();
        Map<String, Object> before = snapshot(article);
        LocalDateTime now = LocalDateTime.now();
        boolean approved = decision == KnowledgeReviewDecision.APPROVE;
        article.setStatus(approved ? KnowledgeStatus.PUBLISHED : KnowledgeStatus.DRAFT);
        article.setReviewedBy(reviewerId);
        article.setReviewedAt(now);
        article.setReviewComment(normalizedComment);
        article.setReviewDueAt(approved ? now.plusDays(REVIEW_VALID_DAYS) : null);
        article.setUpdatedAt(now);
        updateArticle(article, "文章已被其他人更新，请刷新后重试");
        String action = approved ? "KNOWLEDGE_REVIEW_APPROVED" : "KNOWLEDGE_REVIEW_REJECTED";
        articleVersionService.record(article, reviewerId, approved ? "REVIEW_APPROVED" : "REVIEW_REJECTED");
        auditLogService.record(tenantId, reviewerId, "KNOWLEDGE_ARTICLE", article.getId(), action,
                before, snapshot(article), approved ? "审核通过并发布知识文章" : "驳回知识文章审核：" + normalizedComment);
        reviewNotificationService.notifyReviewDecision(article, requesterId, decision);
        return article;
    }

    private void updateArticle(KnowledgeArticle article, String conflictMessage) {
        if (knowledgeMapper.updateById(article) == 0) throw new BusinessException(conflictMessage);
    }

    private void initializeReviewFields(KnowledgeArticle article) {
        article.setReviewRequestedBy(null);
        article.setReviewRequestedAt(null);
        article.setReviewedBy(null);
        article.setReviewedAt(null);
        article.setReviewComment(null);
        article.setSensitiveDataChecked(false);
        article.setScopeDefined(false);
        article.setVerificationStepsComplete(false);
        article.setReviewDueAt(null);
    }

    private String sourceTitle(Ticket ticket) {
        String base = requiredText(ticket.getTitle(), "工单标题", TITLE_MAX_LENGTH);
        String suffix = "｜解决方案";
        return base.length() + suffix.length() <= TITLE_MAX_LENGTH
                ? base + suffix : base.substring(0, TITLE_MAX_LENGTH - suffix.length()) + suffix;
    }

    private String sourceSlug(Ticket ticket) {
        String ticketPart = ticket.getTicketNo() == null || ticket.getTicketNo().isBlank()
                ? String.valueOf(ticket.getId())
                : ticket.getTicketNo().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "");
        return "solution-" + ticketPart + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String sourceContent(Ticket ticket, String resolutionSummary, String verificationResult) {
        StringBuilder content = new StringBuilder();
        appendSection(content, "问题现象", ticket.getDescription(), "未记录问题现象");
        appendSection(content, "影响范围", ticket.getImpactScope(), "未记录影响范围");
        appendSection(content, "处理前尝试", ticket.getAttemptedActions(), "未记录处理前尝试");
        appendSection(content, "解决方案", resolutionSummary, null);
        appendSection(content, "验证结果", verificationResult, null);
        content.append("\n## 来源与复用说明\n\n")
                .append("本草稿由工单 ").append(ticket.getTicketNo()).append(" 的已验收解决方案生成。")
                .append("发布前请移除一次性账号、地址和其他敏感信息，并补充适用范围。\n");
        return requiredText(content.toString(), "内容", CONTENT_MAX_LENGTH);
    }

    private void appendSection(StringBuilder content, String heading, String value, String fallback) {
        content.append("## ").append(heading).append("\n\n");
        if (value == null || value.isBlank()) content.append(fallback == null ? "—" : fallback);
        else content.append(value.trim());
        content.append("\n\n");
    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.trim().isBlank()) {
            throw new BusinessException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String optionalText(String value, String label, int maxLength) {
        if (value == null || value.trim().isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    public record CreateArticleCommand(String title, String slug, String category, String content) { }

    public record UpdateArticleCommand(String title, String slug, String category, String content) { }

    public record KnowledgeDraftResult(KnowledgeArticle article, boolean created) { }

    public record ReviewChecklist(boolean sensitiveDataChecked, boolean scopeDefined,
                                  boolean verificationStepsComplete) { }
}
