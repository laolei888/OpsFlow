package com.opsflow.release;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/releases")
@RequiredArgsConstructor
public class ReleaseController {
    private final ReleaseService releaseService;

    @GetMapping
    public ApiResponse<ReleaseService.ReleaseCenterView> center(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.center(principal.tenantId()));
    }

    @GetMapping("/calendar")
    public ApiResponse<ReleaseService.ReleaseCalendarView> calendar(
            @RequestParam(required = false) LocalDateTime startsAt,
            @RequestParam(required = false) LocalDateTime endsAt,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.calendar(principal.tenantId(), startsAt, endsAt));
    }

    @GetMapping("/freezes")
    public ApiResponse<List<ReleaseFreezeWindow>> freezes(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.freezeWindows(principal.tenantId()));
    }

    @PostMapping("/freezes")
    public ApiResponse<ReleaseFreezeWindow> createFreeze(@Valid @RequestBody FreezeWindowRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(releaseService.createFreeze(request.command(), principal.tenantId(), principal.id()),
                "发布冻结窗口已创建");
    }

    @PutMapping("/freezes/{id}")
    public ApiResponse<ReleaseFreezeWindow> updateFreeze(@PathVariable Long id,
                                                          @Valid @RequestBody FreezeWindowRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(releaseService.updateFreeze(id, request.command(), principal.tenantId(), principal.id()),
                "发布冻结窗口已更新");
    }

    @DeleteMapping("/freezes/{id}")
    public ApiResponse<Void> deleteFreeze(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        releaseService.deleteFreeze(id, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "发布冻结窗口已删除");
    }

    @GetMapping("/{id}")
    public ApiResponse<ReleaseService.ReleaseDetail> detail(@PathVariable Long id,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.detail(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<ReleasePlan> create(@Valid @RequestBody ReleaseRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.create(request.command(), principal.tenantId(), principal.id()),
                "发布单已创建，已生成默认门禁清单");
    }

    @PutMapping("/{id}")
    public ApiResponse<ReleasePlan> update(@PathVariable Long id, @Valid @RequestBody ReleaseRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.update(id, request.command(), principal.tenantId(), principal.id()),
                "发布计划已更新，需重新执行 Go/No-Go 决策");
    }

    @PostMapping("/{id}/changes")
    public ApiResponse<ReleaseService.ReleaseChangeView> linkChange(@PathVariable Long id,
                                                                      @Valid @RequestBody ChangeLinkRequest request,
                                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.linkChange(id, request.changeId(), request.sortOrder(),
                principal.tenantId(), principal.id()), "已关联批准变更");
    }

    @DeleteMapping("/{id}/changes/{changeId}")
    public ApiResponse<Void> unlinkChange(@PathVariable Long id, @PathVariable Long changeId,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        releaseService.unlinkChange(id, changeId, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "已移除关联变更");
    }

    @PostMapping("/{id}/checklist")
    public ApiResponse<ReleaseChecklistItem> addChecklist(@PathVariable Long id,
                                                            @Valid @RequestBody ChecklistRequest request,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.addChecklist(id, request.command(), principal.tenantId(), principal.id()),
                "发布门禁项已添加");
    }

    @PutMapping("/checklist/{itemId}")
    public ApiResponse<ReleaseChecklistItem> updateChecklist(@PathVariable Long itemId,
                                                               @Valid @RequestBody ChecklistUpdateRequest request,
                                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.updateChecklist(itemId, request.command(), principal.tenantId(), principal.id()),
                "门禁执行结果已记录");
    }

    @PostMapping("/{id}/go-no-go")
    public ApiResponse<ReleasePlan> goNoGo(@PathVariable Long id, @Valid @RequestBody GoNoGoRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.decideGoNoGo(id, request.command(), principal.tenantId(), principal.id()),
                request.go() ? "发布门禁已通过，可在窗口内开始实施" : "已记录 No-Go 决策");
    }

    @PostMapping("/{id}/start")
    public ApiResponse<ReleasePlan> start(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.start(id, principal.tenantId(), principal.id()), "发布实施已开始");
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<ReleasePlan> verify(@PathVariable Long id, @RequestBody(required = false) OutcomeRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.verify(id, request == null ? null : request.note(), principal.tenantId(), principal.id()),
                "发布已完成并通过验证");
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<ReleasePlan> rollback(@PathVariable Long id, @Valid @RequestBody OutcomeRequest request,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.rollback(id, request.note(), principal.tenantId(), principal.id()), "发布已标记回滚");
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ReleasePlan> cancel(@PathVariable Long id, @Valid @RequestBody OutcomeRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(releaseService.cancel(id, request.note(), principal.tenantId(), principal.id()), "发布已取消");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理发布治理流程");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置发布冻结策略");
        }
    }

    public record ReleaseRequest(@NotBlank @Size(max = 120) String name,
                                 @Size(max = 4000) String description,
                                 @NotNull ReleaseEnvironment environment,
                                 Long releaseManagerId,
                                 LocalDateTime plannedStart,
                                 LocalDateTime plannedEnd,
                                 @Size(max = 40) List<@NotNull Long> changeIds) {
        ReleaseService.ReleaseCommand command() {
            return new ReleaseService.ReleaseCommand(name, description, environment, releaseManagerId,
                    plannedStart, plannedEnd, changeIds);
        }
    }

    public record ChangeLinkRequest(@NotNull Long changeId, @Max(9999) Integer sortOrder) { }

    public record ChecklistRequest(@NotNull ReleaseChecklistPhase phase,
                                   @NotBlank @Size(max = 180) String title,
                                   Boolean requiredItem, @Max(9999) Integer sortOrder) {
        ReleaseService.ChecklistCommand command() {
            return new ReleaseService.ChecklistCommand(phase, title, requiredItem, sortOrder);
        }
    }

    public record ChecklistUpdateRequest(@NotNull ReleaseChecklistStatus status,
                                         @Size(max = 1000) String note) {
        ReleaseService.ChecklistUpdateCommand command() {
            return new ReleaseService.ChecklistUpdateCommand(status, note);
        }
    }

    public record GoNoGoRequest(@NotNull Boolean go, @Size(max = 500) String comment) {
        ReleaseService.GoNoGoCommand command() { return new ReleaseService.GoNoGoCommand(go, comment); }
    }

    public record FreezeWindowRequest(@NotBlank @Size(max = 120) String name,
                                      ReleaseEnvironment environment,
                                      @NotNull LocalDateTime startsAt,
                                      @NotNull LocalDateTime endsAt,
                                      @NotBlank @Size(max = 1000) String reason,
                                      Boolean enabled) {
        ReleaseService.FreezeWindowCommand command() {
            return new ReleaseService.FreezeWindowCommand(name, environment, startsAt, endsAt, reason, enabled);
        }
    }

    public record OutcomeRequest(@NotBlank @Size(max = 1000) String note) { }
}
