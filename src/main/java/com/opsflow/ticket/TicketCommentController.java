package com.opsflow.ticket;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/comments")
@RequiredArgsConstructor
public class TicketCommentController {
    private final TicketCommentService commentService;

    @GetMapping
    public ApiResponse<List<TicketComment>> list(@PathVariable Long ticketId,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        boolean staff = principal.hasAnyRole("AGENT", "ADMIN");
        return ApiResponse.ok(commentService.list(ticketId, principal.tenantId(), staff,
                staff ? null : principal.id()));
    }

    @PostMapping
    public ApiResponse<TicketComment> create(@PathVariable Long ticketId,
                                             @Valid @RequestBody CreateCommentRequest request,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        boolean staff = principal.hasAnyRole("AGENT", "ADMIN");
        return ApiResponse.ok(commentService.create(ticketId, request.content(), request.visibility(),
                        staff, principal.id(), staff ? null : principal.id(), principal.tenantId()),
                "评论已添加");
    }

    public record CreateCommentRequest(@NotBlank @Size(max = 5000) String content,
                                       TicketCommentVisibility visibility) { }
}