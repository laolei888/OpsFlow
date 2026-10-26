package com.opsflow.knowledge;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private KnowledgeFeedbackService feedbackService;
    @Mock
    private KnowledgeAnalyticsService analyticsService;

    @InjectMocks
    private KnowledgeController controller;

    @Test
    void requesterCannotCreateInternalKnowledgeArticle() {
        KnowledgeController.CreateArticleRequest request = new KnowledgeController.CreateArticleRequest(
                "VPN 指南", "vpn-guide", "网络", "排查步骤");
        AuthPrincipal requester = new AuthPrincipal(7L, 3L, "requester",
                List.of(new SimpleGrantedAuthority("ROLE_REQUESTER")));

        assertThrows(BusinessException.class, () -> controller.create(request, requester));

        verify(knowledgeService, never()).create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
