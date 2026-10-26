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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        KnowledgeArticle article = article(5L, KnowledgeStatus.PUBLISHED);
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
        assertEquals(KnowledgeStatus.PUBLISHED, restored.getStatus());
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
        article.setContent("旧的排障内容");
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
