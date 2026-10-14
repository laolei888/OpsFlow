package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class KnowledgeFeedbackService {
    private final KnowledgeArticleFeedbackMapper feedbackMapper;
    private final KnowledgeMapper knowledgeMapper;

    @Transactional(readOnly = true)
    public FeedbackSummary summary(Long articleId, Long userId, Long tenantId) {
        ensurePublished(articleId, tenantId);
        long helpfulCount = feedbackMapper.selectCount(new LambdaQueryWrapper<KnowledgeArticleFeedback>()
                .eq(KnowledgeArticleFeedback::getTenantId, tenantId)
                .eq(KnowledgeArticleFeedback::getArticleId, articleId)
                .eq(KnowledgeArticleFeedback::getHelpful, true));
        long notHelpfulCount = feedbackMapper.selectCount(new LambdaQueryWrapper<KnowledgeArticleFeedback>()
                .eq(KnowledgeArticleFeedback::getTenantId, tenantId)
                .eq(KnowledgeArticleFeedback::getArticleId, articleId)
                .eq(KnowledgeArticleFeedback::getHelpful, false));
        KnowledgeArticleFeedback mine = feedbackMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticleFeedback>()
                .eq(KnowledgeArticleFeedback::getTenantId, tenantId)
                .eq(KnowledgeArticleFeedback::getArticleId, articleId)
                .eq(KnowledgeArticleFeedback::getUserId, userId));
        return buildSummary(helpfulCount, notHelpfulCount, mine == null ? null : mine.getHelpful());
    }

    @Transactional
    public FeedbackSummary submit(Long articleId, Long userId, Long tenantId, boolean helpful) {
        ensurePublished(articleId, tenantId);
        KnowledgeArticleFeedback feedback = feedbackMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticleFeedback>()
                .eq(KnowledgeArticleFeedback::getTenantId, tenantId)
                .eq(KnowledgeArticleFeedback::getArticleId, articleId)
                .eq(KnowledgeArticleFeedback::getUserId, userId));
        LocalDateTime now = LocalDateTime.now();
        if (feedback == null) {
            feedback = new KnowledgeArticleFeedback();
            feedback.setTenantId(tenantId);
            feedback.setArticleId(articleId);
            feedback.setUserId(userId);
            feedback.setHelpful(helpful);
            feedback.setCreatedAt(now);
            feedback.setUpdatedAt(now);
            try {
                feedbackMapper.insert(feedback);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessException("反馈正在被更新，请稍后重试");
            }
        } else if (!Boolean.valueOf(helpful).equals(feedback.getHelpful())) {
            feedback.setHelpful(helpful);
            feedback.setUpdatedAt(now);
            if (feedbackMapper.updateById(feedback) == 0) {
                throw new BusinessException("反馈已被其他人更新，请刷新后重试");
            }
        }
        return summary(articleId, userId, tenantId);
    }

    @Transactional
    public FeedbackSummary remove(Long articleId, Long userId, Long tenantId) {
        ensurePublished(articleId, tenantId);
        KnowledgeArticleFeedback feedback = feedbackMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticleFeedback>()
                .eq(KnowledgeArticleFeedback::getTenantId, tenantId)
                .eq(KnowledgeArticleFeedback::getArticleId, articleId)
                .eq(KnowledgeArticleFeedback::getUserId, userId));
        if (feedback != null && feedbackMapper.deleteById(feedback.getId()) == 0) {
            throw new BusinessException("反馈已被其他人更新，请刷新后重试");
        }
        return summary(articleId, userId, tenantId);
    }
    private void ensurePublished(Long articleId, Long tenantId) {
        KnowledgeArticle article = knowledgeMapper.selectOne(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getId, articleId)
                .eq(KnowledgeArticle::getTenantId, tenantId)
                .eq(KnowledgeArticle::getStatus, KnowledgeStatus.PUBLISHED));
        if (article == null) throw new BusinessException("知识库文章不存在或尚未发布");
    }

    private FeedbackSummary buildSummary(long helpfulCount, long notHelpfulCount, Boolean myHelpful) {
        long total = helpfulCount + notHelpfulCount;
        int helpfulRate = total == 0 ? 0 : (int) Math.round(helpfulCount * 100.0 / total);
        return new FeedbackSummary(helpfulCount, notHelpfulCount, total, helpfulRate, myHelpful);
    }

    public record FeedbackSummary(long helpfulCount, long notHelpfulCount, long total,
                                  int helpfulRate, Boolean myHelpful) { }
}