package com.opsflow.change;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/changes")
@RequiredArgsConstructor
public class ChangeController {
    private final ChangeService changeService;

    @GetMapping
    public ApiResponse<List<ChangeRequest>> list(
            @RequestParam(required = false) ChangeStatus status,
            @RequestParam(required = false) ChangeRisk risk,
            @RequestParam(required = false) ChangeType type,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(changeService.list(principal.tenantId(), requesterScope(principal), status, risk, type,
                keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChangeService.ChangeDetail> detail(@PathVariable Long id,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(changeService.detail(id, principal.tenantId(), requesterScope(principal)));
    }

    @PostMapping
    public ApiResponse<ChangeRequest> create(@Valid @RequestBody ChangeRequestBody request,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(changeService.create(request.command(), idempotencyKey, requesterScope(principal),
                principal.id(), principal.tenantId()), "变更申请已提交");
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ChangeRequest> approve(@PathVariable Long id,
                                               @Valid @RequestBody DecisionRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(changeService.approve(id, request.comment(), principal.id(), principal.tenantId()),
                "变更申请已通过");
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ChangeRequest> reject(@PathVariable Long id,
                                              @Valid @RequestBody DecisionRequest request,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(changeService.reject(id, request.comment(), principal.id(), principal.tenantId()),
                "变更申请已驳回");
    }

    @PostMapping("/{id}/transition")
    public ApiResponse<ChangeRequest> transition(@PathVariable Long id,
                                                  @RequestParam ChangeStatus target,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(changeService.transition(id, target, principal.id(), principal.tenantId()),
                "变更状态已更新");
    }

    @PostMapping("/{id}/tasks")
    public ApiResponse<ChangeTask> createTask(@PathVariable Long id,
                                               @Valid @RequestBody TaskRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(changeService.createTask(id, request.command(), principal.tenantId(), principal.id()),
                "实施任务已添加");
    }

    @PutMapping("/tasks/{taskId}")
    public ApiResponse<ChangeTask> updateTask(@PathVariable Long taskId,
                                               @Valid @RequestBody TaskUpdateRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(changeService.updateTask(taskId, request.command(), principal.tenantId(), principal.id()),
                "实施任务已更新");
    }

    @PostMapping("/{id}/assets")
    public ApiResponse<ChangeService.ChangeAssetView> linkAsset(
            @PathVariable Long id, @Valid @RequestBody AssetLinkRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(changeService.linkAsset(id, request.command(), principal.tenantId(), principal.id()),
                "配置项已关联到变更");
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    public ApiResponse<Void> unlinkAsset(@PathVariable Long id, @PathVariable Long assetId,
                                         @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        changeService.unlinkAsset(id, assetId, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "变更资产关联已解除");
    }

    private Long requesterScope(AuthPrincipal principal) {
        return principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理变更实施流程");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以审批变更申请");
        }
    }

    public record ChangeRequestBody(
            @NotBlank @Size(max = 160) String title,
            @NotBlank @Size(max = 10000) String description,
            @NotNull ChangeType changeType,
            @NotNull ChangeRisk riskLevel,
            @Size(max = 500) String impactScope,
            @NotBlank @Size(max = 10000) String implementationPlan,
            @Size(max = 10000) String validationPlan,
            @NotBlank @Size(max = 10000) String rollbackPlan,
            LocalDateTime scheduledStart,
            LocalDateTime scheduledEnd,
            Long linkedTicketId,
            @Size(max = 30) List<@Size(max = 160) String> taskTitles) {
        ChangeService.ChangeCommand command() {
            return new ChangeService.ChangeCommand(title, description, changeType, riskLevel, impactScope,
                    implementationPlan, validationPlan, rollbackPlan, scheduledStart, scheduledEnd, linkedTicketId,
                    taskTitles);
        }
    }

    public record DecisionRequest(@Size(max = 500) String comment) { }

    public record TaskRequest(@NotBlank @Size(max = 160) String title, Long assigneeId,
                              @Min(0) @Max(9999) Integer sortOrder) {
        ChangeService.TaskCommand command() {
            return new ChangeService.TaskCommand(title, assigneeId, sortOrder);
        }
    }

    public record TaskUpdateRequest(@Size(max = 160) String title, Long assigneeId, ChangeTaskStatus status) {
        ChangeService.TaskUpdateCommand command() {
            return new ChangeService.TaskUpdateCommand(title, assigneeId, status);
        }
    }

    public record AssetLinkRequest(@NotNull Long assetId, @Size(max = 32) String linkType,
                                   @Size(max = 500) String note) {
        ChangeService.AssetLinkCommand command() {
            return new ChangeService.AssetLinkCommand(assetId, linkType, note);
        }
    }
}
