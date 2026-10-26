package com.opsflow.knowledge;

import com.opsflow.audit.AuditLogService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketService;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketKnowledgeServiceTest {
    @Mock
    private TicketService ticketService;
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TicketKnowledgeService ticketKnowledgeService;

    @Test
    void createsDraftAndLinksAuditBackToTicket() {
        Ticket ticket = ticket();
        KnowledgeArticle article = article();
        when(ticketService.get(1L, 3L)).thenReturn(ticket);
        when(knowledgeService.createFromResolvedTicket(ticket, 9L, 3L))
                .thenReturn(new KnowledgeService.KnowledgeDraftResult(article, true));

        KnowledgeArticle created = ticketKnowledgeService.createDraft(1L, 9L, 3L);

        assertEquals(51L, created.getId());
        verify(auditLogService).record(eq(3L), eq(9L), eq("TICKET"), eq(1L),
                eq("KNOWLEDGE_DRAFT_CREATED"), eq(null), any(), eq("已沉淀为知识草稿 #51"));
    }

    @Test
    void repeatedCreationReturnsExistingDraftWithoutDuplicateTicketAudit() {
        Ticket ticket = ticket();
        KnowledgeArticle article = article();
        when(ticketService.get(1L, 3L)).thenReturn(ticket);
        when(knowledgeService.createFromResolvedTicket(ticket, 9L, 3L))
                .thenReturn(new KnowledgeService.KnowledgeDraftResult(article, false));

        KnowledgeArticle existing = ticketKnowledgeService.createDraft(1L, 9L, 3L);

        assertEquals(51L, existing.getId());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTenantId(3L);
        ticket.setTicketNo("OPF-RES-001");
        ticket.setStatus(TicketStatus.RESOLVED);
        return ticket;
    }

    private KnowledgeArticle article() {
        KnowledgeArticle article = new KnowledgeArticle();
        article.setId(51L);
        article.setTitle("VPN 无法连接｜解决方案");
        article.setStatus(KnowledgeStatus.DRAFT);
        return article;
    }
}
