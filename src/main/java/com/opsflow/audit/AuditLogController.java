package com.opsflow.audit;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {
    private final AuditLogService auditLogService;
    private final TicketService ticketService;

    @GetMapping
    public ApiResponse<List<AuditLog>> list(@PathVariable Long ticketId,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        boolean staff = principal.hasAnyRole("AGENT", "ADMIN");
        ticketService.getVisible(ticketId, principal.tenantId(), staff ? null : principal.id());
        return ApiResponse.ok(auditLogService.listForTicket(ticketId, principal.tenantId()));
    }
}