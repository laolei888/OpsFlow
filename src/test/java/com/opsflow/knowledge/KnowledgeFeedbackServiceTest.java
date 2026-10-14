package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeFeedbackServiceTest {
    @Mock
    private KnowledgeArticleFeedbackMapper feedbackMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    @InjectMocks
    private KnowledgeFeedbackService feedbackService;

    @Test
    void summarizesFeedbackAndCurrentUserVote() {
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(publishedArticle());
        when(feedbackMapper.selectCount(any(Wrapper.class))).thenReturn(7L, 3L);
        KnowledgeArticleFeedback mine = feedback(false);
        when(feedbackMapper.selectOne(any(Wrapper.class))).thenReturn(mine);

        KnowledgeFeedbackService.FeedbackSummary summary = feedbackService.summary(1L, 8L, 3L);

        assertEquals(7L, summary.helpfulCount());
        assertEquals(3L, summary.notHelpfulCount());
        assertEquals(10L, summary.total());
        assertEquals(70, summary.helpfulRate());
        assertFalse(summary.myHelpful());
    }

    @Test
    void removesCurrentUserVoteAndRecalculatesSummary() {
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(publishedArticle(), publishedArticle());
        when(feedbackMapper.selectOne(any(Wrapper.class)))
                .thenReturn(feedback(true), (KnowledgeArticleFeedback) null);
        when(feedbackMapper.deleteById(1L)).thenReturn(1);
        when(feedbackMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L);

        KnowledgeFeedbackService.FeedbackSummary summary = feedbackService.remove(1L, 8L, 3L);

        assertEquals(0L, summary.total());
        assertEquals(0, summary.helpfulRate());
        verify(feedbackMapper).deleteById(1L);
    }
    @Test
    void createsLatestVoteAndReturnsUpdatedSummary() {
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(publishedArticle(), publishedArticle());
        when(feedbackMapper.selectOne(any(Wrapper.class))).thenReturn(null, feedback(true));
        when(feedbackMapper.insert(any(KnowledgeArticleFeedback.class))).thenReturn(1);
        when(feedbackMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);

        KnowledgeFeedbackService.FeedbackSummary summary = feedbackService.submit(1L, 8L, 3L, true);

        assertEquals(1L, summary.helpfulCount());
        assertEquals(0L, summary.notHelpfulCount());
        assertEquals(100, summary.helpfulRate());
        assertEquals(Boolean.TRUE, summary.myHelpful());
        verify(feedbackMapper).insert(any(KnowledgeArticleFeedback.class));
    }

    private KnowledgeArticle publishedArticle() {
        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(1L);
        article.setTenantId(3L);
        article.setStatus(KnowledgeStatus.PUBLISHED);
        return article;
    }

    private KnowledgeArticleFeedback feedback(boolean helpful) {
        KnowledgeArticleFeedback feedback = new KnowledgeArticleFeedback();
        feedback.setId(1L);
        feedback.setTenantId(3L);
        feedback.setArticleId(1L);
        feedback.setUserId(8L);
        feedback.setHelpful(helpful);
        return feedback;
    }
}
