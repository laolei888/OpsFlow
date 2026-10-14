package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
