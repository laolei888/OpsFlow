package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.messaging.AsyncNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeReviewNotificationServiceTest {
    @Mock private KnowledgeMapper knowledgeMapper;
    @Mock private AuthService authService;
    @Mock private AsyncNotificationPublisher notificationPublisher;
    @InjectMocks private KnowledgeReviewNotificationService service;

    @Test
    void notifiesOtherTenantAdminsWhenReviewIsRequested() {
        KnowledgeArticle article = article(KnowledgeStatus.PENDING_REVIEW);
        article.setReviewRequestedAt(LocalDateTime.now());
        when(authService.staffMembers(3L)).thenReturn(List.of(
                staff(7L, "ADMIN"), staff(11L, "ADMIN"), staff(12L, "AGENT")));
        when(notificationPublisher.enqueue(eq(3L), eq(11L), isNull(),
                eq("KNOWLEDGE_REVIEW_REQUESTED"), any(), any(), any(),
                eq("KNOWLEDGE_ARTICLE"), eq(21L))).thenReturn(true);

        int queued = service.notifyReviewRequested(article, 7L);

        assertThat(queued).isEqualTo(1);
        verify(notificationPublisher).enqueue(eq(3L), eq(11L), isNull(),
                eq("KNOWLEDGE_REVIEW_REQUESTED"), contains("VPN 指南"), contains("质量检查"),
                startsWith("KNOWLEDGE_REVIEW_REQUESTED:21:"), eq("KNOWLEDGE_ARTICLE"), eq(21L));
        verify(notificationPublisher, never()).enqueue(eq(3L), eq(7L), any(), any(), any(), any(), any(), any(), any());
        verify(notificationPublisher, never()).enqueue(eq(3L), eq(12L), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void returnsReviewDecisionToOriginalSubmitter() {
        KnowledgeArticle article = article(KnowledgeStatus.DRAFT);
        article.setReviewedAt(LocalDateTime.now());
        article.setReviewComment("补充验证结果");

        service.notifyReviewDecision(article, 7L, KnowledgeReviewDecision.REJECT);

        verify(notificationPublisher).enqueue(eq(3L), eq(7L), isNull(),
                eq("KNOWLEDGE_REVIEW_REJECTED"), contains("审核已驳回"), contains("补充验证结果"),
                startsWith("KNOWLEDGE_REVIEW_REJECTED:21:"), eq("KNOWLEDGE_ARTICLE"), eq(21L));
    }

    @Test
    void truncatesLongReviewCommentsToNotificationStorageLimit() {
        KnowledgeArticle article = article(KnowledgeStatus.DRAFT);
        article.setReviewedAt(LocalDateTime.now());
        article.setReviewComment("需".repeat(1000));
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);

        service.notifyReviewDecision(article, 7L, KnowledgeReviewDecision.REJECT);

        verify(notificationPublisher).enqueue(eq(3L), eq(7L), isNull(),
                eq("KNOWLEDGE_REVIEW_REJECTED"), any(), content.capture(), any(),
                eq("KNOWLEDGE_ARTICLE"), eq(21L));
        assertThat(content.getValue()).hasSize(500).endsWith("...");
    }

    @Test
    void queuesOneDeduplicatedReminderForEachAdminAndDueArticle() {
        KnowledgeArticle due = article(KnowledgeStatus.PUBLISHED);
        due.setReviewDueAt(LocalDateTime.now().minusDays(2));
        when(knowledgeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(due));
        when(authService.staffMembers(3L)).thenReturn(List.of(staff(11L, "ADMIN"), staff(12L, "AGENT")));
        when(notificationPublisher.enqueue(eq(3L), eq(11L), isNull(),
                eq("KNOWLEDGE_REVIEW_DUE"), any(), any(), any(),
                eq("KNOWLEDGE_ARTICLE"), eq(21L))).thenReturn(true);

        int queued = service.scanDueReviews();

        assertThat(queued).isEqualTo(1);
        verify(notificationPublisher).enqueue(eq(3L), eq(11L), isNull(),
                eq("KNOWLEDGE_REVIEW_DUE"), contains("到期复核"), contains("180 天"),
                startsWith("KNOWLEDGE_REVIEW_DUE:21:"), eq("KNOWLEDGE_ARTICLE"), eq(21L));
    }

    private KnowledgeArticle article(KnowledgeStatus status) {
        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(21L);
        article.setTenantId(3L);
        article.setTitle("VPN 指南");
        article.setStatus(status);
        return article;
    }

    private StaffSummary staff(Long id, String role) {
        return new StaffSummary(id, "user" + id, "成员 " + id, List.of(role));
    }
}
