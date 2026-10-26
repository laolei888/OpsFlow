package com.opsflow.knowledge;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/knowledge-draft")
@RequiredArgsConstructor
public class TicketKnowledgeController {
    private final TicketKnowledgeService ticketKnowledgeService;

    @GetMapping
    public ApiResponse<KnowledgeArticle> get(@PathVariable Long ticketId,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(ticketKnowledgeService.findDraft(ticketId, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<KnowledgeArticle> create(@PathVariable Long ticketId,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(ticketKnowledgeService.createDraft(ticketId, principal.id(), principal.tenantId()),
                "解决方案已沉淀为知识草稿");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以沉淀知识草稿");
        }
    }
}
