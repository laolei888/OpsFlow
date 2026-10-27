package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketResolutionCode;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {
    @Mock
    private KnowledgeMapper knowledgeMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private KnowledgeArticleVersionService articleVersionService;

    @Mock
    private KnowledgeReviewNotificationService reviewNotificationService;

    @InjectMocks
    private KnowledgeService knowledgeService;

    @Test
    void updatesArticleContentAndRecordsAudit() {
        KnowledgeArticle article = article(1L, KnowledgeStatus.DRAFT);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article, (KnowledgeArticle) null);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        KnowledgeArticle updated = knowledgeService.update(
                1L,
                new KnowledgeService.UpdateArticleCommand(
                        "更新后的 VPN 指南", "vpn-guide-v2", "网络", "新的排障步骤"),
                7L, 3L);

        assertEquals("更新后的 VPN 指南", updated.getTitle());
        assertEquals("vpn-guide-v2", updated.getSlug());
        assertEquals("新的排障步骤", updated.getContent());
        assertEquals(KnowledgeStatus.DRAFT, updated.getStatus());
        verify(knowledgeMapper).updateById(article);
        verify(auditLogService).record(eq(3L), eq(7L), eq("KNOWLEDGE_ARTICLE"),
                eq(1L), eq("KNOWLEDGE_ARTICLE_UPDATED"), any(), any(), eq("编辑知识文章"));
    }

    @Test
    void archivesAndRestoresArticleWithAudit() {
        KnowledgeArticle article = article(2L, KnowledgeStatus.PUBLISHED);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        KnowledgeArticle archived = knowledgeService.archive(2L, 7L, 3L);
        KnowledgeStatus archivedStatus = archived.getStatus();
        KnowledgeArticle restored = knowledgeService.restore(2L, 7L, 3L);

        assertEquals(KnowledgeStatus.ARCHIVED, archivedStatus);
        assertEquals(KnowledgeStatus.DRAFT, restored.getStatus());
        verify(knowledgeMapper, times(2)).updateById(article);
        verify(auditLogService, times(2)).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void restoresHistoricalContentAndKeepsCurrentStatus() {
        KnowledgeArticle article = article(5L, KnowledgeStatus.DRAFT);
        KnowledgeArticleVersion historical = new KnowledgeArticleVersion();
        historical.setId(12L);
        historical.setArticleId(5L);
        historical.setTenantId(3L);
        historical.setTitle("旧版 VPN 指南");
        historical.setSlug("vpn-guide-old");
        historical.setCategory("网络");
        historical.setContent("旧版排障步骤");
        historical.setStatus(KnowledgeStatus.DRAFT);

        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article, (KnowledgeArticle) null);
        when(articleVersionService.getForArticle(5L, 12L, 3L)).thenReturn(historical);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        KnowledgeArticle restored = knowledgeService.restoreVersion(5L, 12L, 7L, 3L);

        assertEquals("旧版 VPN 指南", restored.getTitle());
        assertEquals("vpn-guide-old", restored.getSlug());
        assertEquals("旧版排障步骤", restored.getContent());
        assertEquals(KnowledgeStatus.DRAFT, restored.getStatus());
        verify(articleVersionService).record(article, 7L, "VERSION_RESTORED");
        verify(auditLogService).record(eq(3L), eq(7L), eq("KNOWLEDGE_ARTICLE"),
                eq(5L), eq("KNOWLEDGE_ARTICLE_VERSION_RESTORED"), any(), any(), eq("恢复历史版本"));
    }
    @Test
    void loadsPublishedArticleAndIncrementsViewCount() {
        KnowledgeArticle article = article(4L, KnowledgeStatus.PUBLISHED);
        article.setViewCount(2L);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        KnowledgeArticle loaded = knowledgeService.getPublished(4L, 3L);

        assertEquals(3L, loaded.getViewCount());
        verify(knowledgeMapper).updateById(article);
    }
    @Test
    void refusesToPublishArchivedArticleUntilRestored() {
        KnowledgeArticle article = article(3L, KnowledgeStatus.ARCHIVED);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);

        assertThrows(BusinessException.class, () -> knowledgeService.publish(3L, 7L, 3L));

        verify(knowledgeMapper, never()).updateById(any(KnowledgeArticle.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitsDraftForReviewAfterAllQualityChecksPass() {
        KnowledgeArticle article = article(6L, KnowledgeStatus.DRAFT);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        KnowledgeArticle submitted = knowledgeService.submitForReview(6L,
                new KnowledgeService.ReviewChecklist(true, true, true), 7L, 3L);

        assertEquals(KnowledgeStatus.PENDING_REVIEW, submitted.getStatus());
        assertEquals(7L, submitted.getReviewRequestedBy());
        assertTrue(submitted.getSensitiveDataChecked());
        assertTrue(submitted.getScopeDefined());
        assertTrue(submitted.getVerificationStepsComplete());
        verify(articleVersionService).record(article, 7L, "SUBMITTED_FOR_REVIEW");
        verify(auditLogService).record(eq(3L), eq(7L), eq("KNOWLEDGE_ARTICLE"), eq(6L),
                eq("KNOWLEDGE_REVIEW_SUBMITTED"), any(), any(), eq("提交知识文章审核"));
        verify(reviewNotificationService).notifyReviewRequested(article, 7L);
    }

    @Test
    void rejectsReviewSubmissionWhenChecklistIsIncomplete() {
        KnowledgeArticle article = article(7L, KnowledgeStatus.DRAFT);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);

        assertThrows(BusinessException.class, () -> knowledgeService.submitForReview(7L,
                new KnowledgeService.ReviewChecklist(true, false, true), 7L, 3L));

        assertEquals(KnowledgeStatus.DRAFT, article.getStatus());
        verify(knowledgeMapper, never()).updateById(any(KnowledgeArticle.class));
    }

    @Test
    void refusesDirectPublishBeforeReviewSubmission() {
        KnowledgeArticle article = article(8L, KnowledgeStatus.DRAFT);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);

        assertThrows(BusinessException.class, () -> knowledgeService.publish(8L, 9L, 3L));

        verify(knowledgeMapper, never()).updateById(any(KnowledgeArticle.class));
    }

    @Test
    void approvesPendingArticleAndSchedulesPeriodicReview() {
        KnowledgeArticle article = article(9L, KnowledgeStatus.PENDING_REVIEW);
        article.setReviewRequestedBy(7L);
        article.setReviewRequestedAt(LocalDateTime.now().minusHours(1));
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        KnowledgeArticle published = knowledgeService.review(
                9L, KnowledgeReviewDecision.APPROVE, "内容与步骤完整", 11L, 3L);

        assertEquals(KnowledgeStatus.PUBLISHED, published.getStatus());
        assertEquals(11L, published.getReviewedBy());
        assertEquals("内容与步骤完整", published.getReviewComment());
        assertTrue(published.getReviewDueAt().isAfter(LocalDateTime.now().plusDays(179)));
        verify(articleVersionService).record(article, 11L, "REVIEW_APPROVED");
        verify(reviewNotificationService).notifyReviewDecision(
                article, 7L, KnowledgeReviewDecision.APPROVE);
    }

    @Test
    void rejectionRequiresReasonAndReturnsArticleToDraft() {
        KnowledgeArticle article = article(10L, KnowledgeStatus.PENDING_REVIEW);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article, article);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        assertThrows(BusinessException.class, () -> knowledgeService.review(
                10L, KnowledgeReviewDecision.REJECT, " ", 11L, 3L));
        KnowledgeArticle rejected = knowledgeService.review(
                10L, KnowledgeReviewDecision.REJECT, "补充回滚后的验证结果", 11L, 3L);

        assertEquals(KnowledgeStatus.DRAFT, rejected.getStatus());
        assertEquals("补充回滚后的验证结果", rejected.getReviewComment());
        assertNull(rejected.getReviewDueAt());
        verify(articleVersionService).record(article, 11L, "REVIEW_REJECTED");
        verify(reviewNotificationService).notifyReviewDecision(
                article, null, KnowledgeReviewDecision.REJECT);
    }

    @Test
    void onlySubmitterOrAdminCanWithdrawPendingReview() {
        KnowledgeArticle article = article(11L, KnowledgeStatus.PENDING_REVIEW);
        article.setReviewRequestedBy(7L);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(article, article);
        when(knowledgeMapper.updateById(article)).thenReturn(1);

        assertThrows(BusinessException.class,
                () -> knowledgeService.withdrawReview(11L, 8L, false, 3L));
        KnowledgeArticle withdrawn = knowledgeService.withdrawReview(11L, 7L, false, 3L);

        assertEquals(KnowledgeStatus.DRAFT, withdrawn.getStatus());
        assertNull(withdrawn.getReviewRequestedBy());
        assertFalse(withdrawn.getSensitiveDataChecked());
        verify(articleVersionService).record(article, 7L, "REVIEW_WITHDRAWN");
        verify(reviewNotificationService).notifyReviewWithdrawn(article, 7L, 7L);
    }

    @Test
    void createsReusableDraftFromConfirmedTicketResolution() {
        Ticket ticket = resolvedTicket();
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(knowledgeMapper.insert(any(KnowledgeArticle.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeArticle.class).setId(41L);
            return 1;
        });

        KnowledgeService.KnowledgeDraftResult result =
                knowledgeService.createFromResolvedTicket(ticket, 9L, 3L);

        KnowledgeArticle article = result.article();
        assertEquals(true, result.created());
        assertEquals(41L, article.getId());
        assertEquals(1L, article.getSourceTicketId());
        assertEquals("OPF-RES-001", article.getSourceTicketNo());
        assertEquals(KnowledgeStatus.DRAFT, article.getStatus());
        assertEquals("VPN 无法连接｜解决方案", article.getTitle());
        assertEquals(true, article.getContent().contains("## 解决方案"));
        assertEquals(true, article.getContent().contains("修复网关路由配置"));
        assertEquals(true, article.getContent().contains("## 验证结果"));
        verify(articleVersionService).record(article, 9L, "CREATED_FROM_TICKET");
        verify(auditLogService).record(eq(3L), eq(9L), eq("KNOWLEDGE_ARTICLE"), eq(41L),
                eq("KNOWLEDGE_DRAFT_CREATED_FROM_TICKET"), any(), any(), any());
    }

    @Test
    void reusesExistingDraftForSameSourceTicket() {
        Ticket ticket = resolvedTicket();
        KnowledgeArticle existing = article(42L, KnowledgeStatus.DRAFT);
        existing.setSourceTicketId(1L);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        KnowledgeService.KnowledgeDraftResult result =
                knowledgeService.createFromResolvedTicket(ticket, 9L, 3L);

        assertEquals(false, result.created());
        assertEquals(42L, result.article().getId());
        verify(knowledgeMapper, never()).insert(any(KnowledgeArticle.class));
        verify(articleVersionService, never()).record(any(), any(), any());
    }

    @Test
    void returnsConcurrentDraftAfterUniqueConstraintConflict() {
        Ticket ticket = resolvedTicket();
        KnowledgeArticle concurrent = article(43L, KnowledgeStatus.DRAFT);
        concurrent.setSourceTicketId(1L);
        when(knowledgeMapper.selectOne(any(Wrapper.class))).thenReturn(null, concurrent);
        when(knowledgeMapper.insert(any(KnowledgeArticle.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate source ticket"));

        KnowledgeService.KnowledgeDraftResult result =
                knowledgeService.createFromResolvedTicket(ticket, 9L, 3L);

        assertEquals(false, result.created());
        assertEquals(43L, result.article().getId());
        verify(articleVersionService, never()).record(any(), any(), any());
    }

    @Test
    void rejectsKnowledgeDraftBeforeRequesterConfirmation() {
        Ticket ticket = resolvedTicket();
        ticket.setStatus(TicketStatus.WAITING_CONFIRMATION);
        ticket.setConfirmedAt(null);

        assertThrows(BusinessException.class,
                () -> knowledgeService.createFromResolvedTicket(ticket, 9L, 3L));

        verify(knowledgeMapper, never()).insert(any(KnowledgeArticle.class));
    }

    private KnowledgeArticle article(Long id, KnowledgeStatus status) {
        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(id);
        article.setTenantId(3L);
        article.setAuthorId(7L);
        article.setTitle("VPN 指南");
        article.setSlug("vpn-guide-" + id);
        article.setCategory("网络");
        article.setContent("本文说明 VPN 连接失败时的适用范围、前置条件、客户端日志定位、网关路由修复、连接恢复验证和失败回滚方法；同时记录预期现象、异常分支、检查命令、执行顺序与验证标准，确保不同处理人可以根据步骤完整复现问题、确认最终结果并在失败时安全恢复原配置。");
        article.setStatus(status);
        article.setVersion(0);
        article.setViewCount(0L);
        return article;
    }

    private Ticket resolvedTicket() {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTenantId(3L);
        ticket.setTicketNo("OPF-RES-001");
        ticket.setTitle("VPN 无法连接");
        ticket.setDescription("客户端持续连接超时");
        ticket.setImpactScope("研发团队无法远程办公");
        ticket.setAttemptedActions("重启客户端并切换网络");
        ticket.setCategory("网络");
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolutionCode(TicketResolutionCode.FIXED);
        ticket.setResolutionSummary("修复网关路由配置");
        ticket.setVerificationResult("申请人复测连接成功");
        ticket.setConfirmedAt(LocalDateTime.now());
        return ticket;
    }
}
