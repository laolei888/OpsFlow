package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeService {
    private static final int TITLE_MAX_LENGTH = 160;
    private static final int SLUG_MAX_LENGTH = 180;
    private static final int CATEGORY_MAX_LENGTH = 64;
    private static final int CONTENT_MAX_LENGTH = 50000;

    private final KnowledgeMapper knowledgeMapper;
    private final AuditLogService auditLogService;
    private final KnowledgeArticleVersionService articleVersionService;

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

    @Transactional
    public KnowledgeArticle update(Long id, UpdateArticleCommand command, Long actorId, Long tenantId) {
        KnowledgeArticle article = getManageable(id, tenantId);
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
        if (article.getStatus() == KnowledgeStatus.ARCHIVED) {
            throw new BusinessException("归档文章不能直接发布，请先恢复为草稿");
        }
        if (article.getStatus() == KnowledgeStatus.PUBLISHED) return article;
        return changeStatus(article, KnowledgeStatus.PUBLISHED, actorId, tenantId,
                "KNOWLEDGE_ARTICLE_PUBLISHED", "发布知识文章");
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
        return snapshot;
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

    public record CreateArticleCommand(String title, String slug, String category, String content) { }

    public record UpdateArticleCommand(String title, String slug, String category, String content) { }
}