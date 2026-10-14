package com.opsflow.knowledge;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeArticleVersionServiceTest {
    @Mock
    private KnowledgeArticleVersionMapper versionMapper;

    @InjectMocks
    private KnowledgeArticleVersionService versionService;

    @Test
    void recordsNextVersionWithArticleSnapshot() {
        KnowledgeArticleVersion previous = version(3);
        when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(previous);
        when(versionMapper.insert(any(KnowledgeArticleVersion.class))).thenReturn(1);

        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(9L);
        article.setTenantId(3L);
        article.setAuthorId(7L);
        article.setTitle("VPN 新指南");
        article.setSlug("vpn-new");
        article.setCategory("网络");
        article.setContent("新的排障步骤");
        article.setStatus(KnowledgeStatus.PUBLISHED);

        KnowledgeArticleVersion saved = versionService.record(article, 8L, "UPDATED");

        assertEquals(4, saved.getVersionNo());
        assertEquals(9L, saved.getArticleId());
        assertEquals(8L, saved.getChangedBy());
        assertEquals("UPDATED", saved.getChangeType());
        assertEquals("新的排障步骤", saved.getContent());
        verify(versionMapper).insert(any(KnowledgeArticleVersion.class));
    }

    @Test
    void startsAtVersionOneWhenArticleHasNoHistory() {
        when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(versionMapper.insert(any(KnowledgeArticleVersion.class))).thenReturn(1);

        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(10L);
        article.setTenantId(3L);
        article.setAuthorId(7L);
        article.setTitle("账号指南");
        article.setSlug("account-guide");
        article.setCategory("账号");
        article.setContent("重置密码");
        article.setStatus(KnowledgeStatus.DRAFT);

        KnowledgeArticleVersion saved = versionService.record(article, null, "CREATED");

        assertEquals(1, saved.getVersionNo());
        assertEquals(7L, saved.getChangedBy());
    }

    @Test
    void listsOnlyTenantArticleHistory() {
        KnowledgeArticleVersion latest = version(2);
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(latest));

        List<KnowledgeArticleVersion> result = versionService.listForArticle(11L, 3L);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getVersionNo());
    }

    private KnowledgeArticleVersion version(int versionNo) {
        KnowledgeArticleVersion version = new KnowledgeArticleVersion();
        version.setId((long) versionNo);
        version.setTenantId(3L);
        version.setArticleId(11L);
        version.setVersionNo(versionNo);
        version.setTitle("历史文章");
        version.setSlug("history");
        version.setCategory("网络");
        version.setContent("历史内容");
        version.setStatus(KnowledgeStatus.DRAFT);
        version.setChangedBy(7L);
        version.setChangeType("UPDATED");
        return version;
    }
}