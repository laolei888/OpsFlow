package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeArticleVersionService {
    private static final int MAX_VERSIONS = 100;

    private final KnowledgeArticleVersionMapper versionMapper;

    public List<KnowledgeArticleVersion> listForArticle(Long articleId, Long tenantId) {
        return versionMapper.selectList(new LambdaQueryWrapper<KnowledgeArticleVersion>()
                .eq(KnowledgeArticleVersion::getArticleId, articleId)
                .eq(KnowledgeArticleVersion::getTenantId, tenantId)
                .orderByDesc(KnowledgeArticleVersion::getVersionNo)
                .last("LIMIT " + MAX_VERSIONS));
    }

    public KnowledgeArticleVersion getForArticle(Long articleId, Long versionId, Long tenantId) {
        KnowledgeArticleVersion version = versionMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticleVersion>()
                .eq(KnowledgeArticleVersion::getId, versionId)
                .eq(KnowledgeArticleVersion::getArticleId, articleId)
                .eq(KnowledgeArticleVersion::getTenantId, tenantId));
        if (version == null) throw new BusinessException("文章版本不存在");
        return version;
    }
    public KnowledgeArticleVersion record(KnowledgeArticle article, Long changedBy, String changeType) {
        KnowledgeArticleVersion latest = versionMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticleVersion>()
                .eq(KnowledgeArticleVersion::getArticleId, article.getId())
                .eq(KnowledgeArticleVersion::getTenantId, article.getTenantId())
                .orderByDesc(KnowledgeArticleVersion::getVersionNo)
                .last("LIMIT 1"));
        int nextVersion = latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;

        KnowledgeArticleVersion version = new KnowledgeArticleVersion();
        version.setTenantId(article.getTenantId());
        version.setArticleId(article.getId());
        version.setVersionNo(nextVersion);
        version.setTitle(article.getTitle());
        version.setSlug(article.getSlug());
        version.setCategory(article.getCategory());
        version.setContent(article.getContent());
        version.setStatus(article.getStatus());
        version.setChangedBy(changedBy == null ? article.getAuthorId() : changedBy);
        version.setChangeType(changeType);
        version.setCreatedAt(LocalDateTime.now());
        try {
            versionMapper.insert(version);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("文章版本正在被其他人更新，请刷新后重试");
        }
        return version;
    }
}