package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeAnalyticsService {
    private static final int TOP_LIMIT = 5;

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeArticleFeedbackMapper feedbackMapper;

    @Transactional(readOnly = true)
    public KnowledgeAnalytics summarize(Long tenantId) {
        List<KnowledgeArticle> articles = knowledgeMapper.selectList(new LambdaQueryWrapper<KnowledgeArticle>()
                .eq(KnowledgeArticle::getTenantId, tenantId));
        List<KnowledgeArticleFeedback> feedback = feedbackMapper.selectList(new LambdaQueryWrapper<KnowledgeArticleFeedback>()
                .eq(KnowledgeArticleFeedback::getTenantId, tenantId));

        Map<Long, FeedbackCounts> feedbackByArticle = new HashMap<>();
        for (KnowledgeArticleFeedback item : feedback) {
            feedbackByArticle.computeIfAbsent(item.getArticleId(), ignored -> new FeedbackCounts())
                    .add(item.getHelpful());
        }

        List<ArticleMetric> metrics = articles.stream()
                .map(article -> metric(article, feedbackByArticle.get(article.getId())))
                .toList();
        List<ArticleMetric> publishedMetrics = metrics.stream()
                .filter(item -> item.status() == KnowledgeStatus.PUBLISHED)
                .toList();

        long totalHelpful = feedback.stream().filter(item -> Boolean.TRUE.equals(item.getHelpful())).count();
        long totalFeedback = feedback.size();
        int helpfulRate = totalFeedback == 0 ? 0 : (int) Math.round(totalHelpful * 100.0 / totalFeedback);

        List<ArticleMetric> topViewed = publishedMetrics.stream()
                .sorted(Comparator.comparingLong(ArticleMetric::viewCount).reversed())
                .limit(TOP_LIMIT)
                .toList();
        List<ArticleMetric> needsReview = publishedMetrics.stream()
                .filter(item -> item.totalFeedback() > 0 && item.helpfulRate() < 70)
                .sorted(Comparator.comparingInt(ArticleMetric::helpfulRate)
                        .thenComparing(Comparator.comparingLong(ArticleMetric::totalFeedback).reversed()))
                .limit(TOP_LIMIT)
                .toList();

        return new KnowledgeAnalytics(
                articles.size(),
                articles.stream().filter(item -> item.getStatus() == KnowledgeStatus.PUBLISHED).count(),
                articles.stream().filter(item -> item.getStatus() == KnowledgeStatus.DRAFT).count(),
                articles.stream().filter(item -> item.getStatus() == KnowledgeStatus.ARCHIVED).count(),
                articles.stream().mapToLong(item -> item.getViewCount() == null ? 0L : item.getViewCount()).sum(),
                totalFeedback,
                helpfulRate,
                topViewed,
                needsReview);
    }

    private ArticleMetric metric(KnowledgeArticle article, FeedbackCounts counts) {
        FeedbackCounts safeCounts = counts == null ? new FeedbackCounts() : counts;
        long total = safeCounts.helpful + safeCounts.notHelpful;
        int rate = total == 0 ? 0 : (int) Math.round(safeCounts.helpful * 100.0 / total);
        return new ArticleMetric(
                article.getId(),
                article.getTitle(),
                article.getCategory(),
                article.getStatus(),
                article.getViewCount() == null ? 0L : article.getViewCount(),
                safeCounts.helpful,
                safeCounts.notHelpful,
                total,
                rate);
    }

    private static final class FeedbackCounts {
        private long helpful;
        private long notHelpful;

        private void add(Boolean value) {
            if (Boolean.TRUE.equals(value)) helpful++;
            else notHelpful++;
        }
    }

    public record KnowledgeAnalytics(
            long totalArticles,
            long publishedArticles,
            long draftArticles,
            long archivedArticles,
            long totalViews,
            long totalFeedback,
            int helpfulRate,
            List<ArticleMetric> topViewed,
            List<ArticleMetric> needsReview) { }

    public record ArticleMetric(
            Long articleId,
            String title,
            String category,
            KnowledgeStatus status,
            long viewCount,
            long helpfulCount,
            long notHelpfulCount,
            long totalFeedback,
            int helpfulRate) { }
}