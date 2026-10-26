package com.opsflow.knowledge;

import com.opsflow.audit.AuditLogService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TicketKnowledgeService {
    private final TicketService ticketService;
    private final KnowledgeService knowledgeService;
    private final AuditLogService auditLogService;

    public KnowledgeArticle findDraft(Long ticketId, Long tenantId) {
        ticketService.get(ticketId, tenantId);
        return knowledgeService.findBySourceTicket(ticketId, tenantId);
    }

    @Transactional
    public KnowledgeArticle createDraft(Long ticketId, Long actorId, Long tenantId) {
        Ticket ticket = ticketService.get(ticketId, tenantId);
        KnowledgeService.KnowledgeDraftResult result =
                knowledgeService.createFromResolvedTicket(ticket, actorId, tenantId);
        if (result.created()) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("knowledgeArticleId", result.article().getId());
            after.put("knowledgeArticleTitle", result.article().getTitle());
            after.put("knowledgeArticleStatus", result.article().getStatus().name());
            auditLogService.record(tenantId, actorId, "TICKET", ticketId,
                    "KNOWLEDGE_DRAFT_CREATED", null, after,
                    "已沉淀为知识草稿 #" + result.article().getId());
        }
        return result.article();
    }
}
