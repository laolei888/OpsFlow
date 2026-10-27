package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeAnalyticsServiceTest {
    @Mock
    private KnowledgeMapper knowledgeMapper;

    @Mock
    private KnowledgeArticleFeedbackMapper feedbackMapper;

    @InjectMocks
    private KnowledgeAnalyticsService analyticsService;

    @Test
    void summarizesArticleHealthAndReviewQueue() {
        KnowledgeArticle popular = article(1L, "VPN 指南", KnowledgeStatus.PUBLISHED, 20L);
        KnowledgeArticle weak = article(2L, "账号指南", KnowledgeStatus.PUBLISHED, 10L);
        KnowledgeArticle draft = article(3L, "草稿", KnowledgeStatus.DRAFT, 100L);
        KnowledgeArticle archived = article(4L, "旧指南", KnowledgeStatus.ARCHIVED, 5L);
        KnowledgeArticle pending = article(5L, "待审核指南", KnowledgeStatus.PENDING_REVIEW, 7L);
        popular.setReviewDueAt(LocalDateTime.now().minusDays(1));

        when(knowledgeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(popular, weak, draft, archived, pending));
        when(feedbackMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        feedback(1L, true),
                        feedback(1L, true),
                        feedback(1L, true),
                        feedback(1L, false),
                        feedback(2L, false),
                        feedback(2L, false)));

        KnowledgeAnalyticsService.KnowledgeAnalytics result = analyticsService.summarize(3L);

        assertEquals(5L, result.totalArticles());
        assertEquals(2L, result.publishedArticles());
        assertEquals(1L, result.draftArticles());
        assertEquals(1L, result.pendingReviewArticles());
        assertEquals(1L, result.archivedArticles());
        assertEquals(1L, result.overdueReviewArticles());
        assertEquals(142L, result.totalViews());
        assertEquals(6L, result.totalFeedback());
        assertEquals(50, result.helpfulRate());
        assertEquals(1L, result.topViewed().get(0).articleId());
        assertEquals(2L, result.needsReview().get(0).articleId());
        assertEquals(0, result.needsReview().get(0).helpfulRate());
    }

    private KnowledgeArticle article(Long id, String title, KnowledgeStatus status, Long viewCount) {
        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(id);
        article.setTenantId(3L);
        article.setTitle(title);
        article.setCategory("网络");
        article.setStatus(status);
        article.setViewCount(viewCount);
        return article;
    }

    private KnowledgeArticleFeedback feedback(Long articleId, boolean helpful) {
        KnowledgeArticleFeedback feedback = new KnowledgeArticleFeedback();
        feedback.setTenantId(3L);
        feedback.setArticleId(articleId);
        feedback.setHelpful(helpful);
        return feedback;
    }
}
