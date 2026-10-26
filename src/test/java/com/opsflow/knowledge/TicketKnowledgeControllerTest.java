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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketKnowledgeControllerTest {
    @Mock
    private TicketKnowledgeService ticketKnowledgeService;

    @InjectMocks
    private TicketKnowledgeController controller;

    @Test
    void staffCanCreateKnowledgeDraft() {
        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(51L);
        when(ticketKnowledgeService.createDraft(1L, 9L, 3L)).thenReturn(article);

        KnowledgeArticle response = controller.create(1L, principal(9L, "AGENT")).data();

        assertEquals(51L, response.getId());
        verify(ticketKnowledgeService).createDraft(1L, 9L, 3L);
    }

    @Test
    void requesterCannotReadInternalKnowledgeDraftLink() {
        assertThrows(BusinessException.class, () -> controller.get(1L, principal(7L, "REQUESTER")));

        verify(ticketKnowledgeService, never()).findDraft(1L, 3L);
    }

    private AuthPrincipal principal(Long id, String role) {
        return new AuthPrincipal(id, 3L, role.toLowerCase(),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
