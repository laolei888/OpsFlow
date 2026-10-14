package com.opsflow.automation;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/automation-rules")
@RequiredArgsConstructor
public class AutomationRuleController {
    private final AutomationRuleService automationRuleService;

    @GetMapping
    public ApiResponse<List<AutomationRuleView>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(automationRuleService.list(principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<AutomationRuleView> create(@Valid @RequestBody RuleRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(automationRuleService.create(request.toCommand(), principal.tenantId(), principal.id()),
                "自动化规则已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<AutomationRuleView> update(@PathVariable Long id, @Valid @RequestBody RuleRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(automationRuleService.update(id, request.toCommand(), principal.tenantId(), principal.id()),
                "自动化规则已更新");
    }

    @PatchMapping("/{id}/enabled")
    public ApiResponse<AutomationRuleView> toggle(@PathVariable Long id, @RequestParam boolean enabled,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(automationRuleService.toggle(id, enabled, principal.tenantId(), principal.id()),
                enabled ? "自动化规则已启用" : "自动化规则已停用");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        automationRuleService.delete(id, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "自动化规则已删除");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理自动化规则");
        }
    }

    public record RuleRequest(
            @NotBlank @Size(max = 80) String name,
            Boolean enabled,
            @Min(0) @Max(9999) Integer sortOrder,
            @Size(max = 64) String matchCategory,
            TicketPriority matchPriority,
            @Size(max = 80) String matchKeyword,
            Long targetAssigneeId,
            Long targetGroupId,
            AutomationAssignmentStrategy assignmentStrategy,
            TicketPriority targetPriority,
            TicketStatus targetStatus) {
        public AutomationRuleService.RuleCommand toCommand() {
            return new AutomationRuleService.RuleCommand(name, enabled, sortOrder, matchCategory, matchPriority,
                    matchKeyword, targetAssigneeId, targetGroupId, assignmentStrategy, targetPriority, targetStatus);
        }
    }
}
