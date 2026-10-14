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
@RequestMapping("/api/v1/ticket-views")
@RequiredArgsConstructor
public class TicketSavedViewController {
    private final TicketSavedViewService savedViewService;

    @GetMapping
    public ApiResponse<List<TicketSavedViewService.TicketSavedViewView>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(savedViewService.list(principal.tenantId(), principal.id(), isStaff(principal)));
    }

    @PostMapping
    public ApiResponse<TicketSavedViewService.TicketSavedViewView> create(
            @Valid @RequestBody SavedViewRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(savedViewService.create(request.toCommand(), principal.tenantId(),
                principal.id(), isStaff(principal)), "保存视图已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<TicketSavedViewService.TicketSavedViewView> update(
            @PathVariable Long id, @Valid @RequestBody SavedViewRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(savedViewService.update(id, request.toCommand(), principal.tenantId(),
                principal.id(), isStaff(principal)), "保存视图已更新");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthPrincipal principal) {
        savedViewService.delete(id, principal.tenantId(), principal.id(), principal.hasAnyRole("ADMIN"));
        return ApiResponse.ok(null, "保存视图已删除");
    }

    private boolean isStaff(AuthPrincipal principal) {
        return principal.hasAnyRole("AGENT", "ADMIN");
    }

    public record SavedViewRequest(
            @NotBlank @Size(max = 60) String name,
            String visibility,
            TicketStatus status,
            TicketPriority priority,
            TicketSort sort,
            @Size(max = 120) String keyword,
            boolean overdue,
            boolean mine) {
        public TicketSavedViewService.SavedViewCommand toCommand() {
            return new TicketSavedViewService.SavedViewCommand(name, visibility, status, priority,
                    keyword, overdue, mine, sort);
        }
    }
}