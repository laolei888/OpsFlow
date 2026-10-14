package com.opsflow.supportgroup;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.Ticket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/support-groups")
@RequiredArgsConstructor
public class SupportGroupController {
    private final SupportGroupService supportGroupService;

    @GetMapping
    public ApiResponse<List<SupportGroupService.GroupSummary>> list(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(supportGroupService.list(principal.tenantId(), enabled, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupportGroupService.GroupDetail> detail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(supportGroupService.detail(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<SupportGroup> create(@Valid @RequestBody GroupRequest request,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(supportGroupService.create(request.command(), principal.id(), principal.tenantId()),
                "支持组已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<SupportGroup> update(@PathVariable Long id,
                                             @Valid @RequestBody GroupRequest request,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(supportGroupService.update(id, request.command(), principal.id(), principal.tenantId()),
                "支持组资料已更新");
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<SupportGroup> toggle(@PathVariable Long id,
                                             @Valid @RequestBody ToggleRequest request,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(supportGroupService.toggle(id, request.enabled(), principal.id(), principal.tenantId()),
                request.enabled() ? "支持组已启用" : "支持组已停用");
    }

    @PostMapping("/{id}/members")
    public ApiResponse<SupportGroupMember> addMember(@PathVariable Long id,
                                                      @Valid @RequestBody MemberRequest request,
                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(supportGroupService.addMember(id, request.command(), principal.id(), principal.tenantId()),
                "支持组成员已保存");
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long userId,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        supportGroupService.removeMember(id, userId, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "支持组成员已移除");
    }

    @PostMapping("/{id}/tickets/{ticketId}")
    public ApiResponse<Ticket> routeTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(supportGroupService.routeTicket(id, ticketId, principal.id(), principal.tenantId()),
                "工单已路由到支持组");
    }

    @DeleteMapping("/{id}/tickets/{ticketId}")
    public ApiResponse<Ticket> unrouteTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(supportGroupService.unrouteTicket(id, ticketId, principal.id(), principal.tenantId()),
                "工单已移出支持组队列");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以访问支持组队列");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以维护支持组");
        }
    }

    public record GroupRequest(
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{1,31}", message = "支持组编码格式不正确") String groupCode,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description,
            Long managerId) {
        SupportGroupService.GroupCommand command() {
            return new SupportGroupService.GroupCommand(groupCode, name, description, managerId);
        }
    }

    public record MemberRequest(@NotNull Long userId, SupportGroupMemberRole memberRole) {
        SupportGroupService.MemberCommand command() {
            return new SupportGroupService.MemberCommand(userId, memberRole);
        }
    }

    public record ToggleRequest(@NotNull Boolean enabled) { }
}
