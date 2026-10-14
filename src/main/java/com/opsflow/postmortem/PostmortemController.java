package com.opsflow.postmortem;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/postmortems")
@RequiredArgsConstructor
public class PostmortemController {
    private final PostmortemService postmortemService;

    @GetMapping
    public ApiResponse<List<PostmortemService.PostmortemListItem>> list(
            @RequestParam(required = false) PostmortemStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.list(principal.tenantId(), status, keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<PostmortemService.PostmortemDetail> detail(@PathVariable Long id,
                                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.detail(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<IncidentPostmortem> create(@Valid @RequestBody CreateRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.create(request.command(), principal.id(), principal.tenantId()),
                "重大事件复盘已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<IncidentPostmortem> update(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.update(id, request.command(), principal.id(), principal.tenantId()),
                "复盘内容已保存");
    }

    @PostMapping("/{id}/actions")
    public ApiResponse<IncidentPostmortemAction> addAction(@PathVariable Long id,
                                                            @Valid @RequestBody ActionCreateRequest request,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.addAction(id, request.command(), principal.id(), principal.tenantId()),
                "整改行动项已添加");
    }

    @PutMapping("/actions/{actionId}")
    public ApiResponse<IncidentPostmortemAction> updateAction(@PathVariable Long actionId,
                                                               @Valid @RequestBody ActionUpdateRequest request,
                                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.updateAction(actionId, request.command(), principal.id(), principal.tenantId()),
                "整改行动项已更新");
    }

    @DeleteMapping("/actions/{actionId}")
    public ApiResponse<Void> deleteAction(@PathVariable Long actionId,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        postmortemService.deleteAction(actionId, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "整改行动项已删除");
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<IncidentPostmortem> submit(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(postmortemService.submit(id, principal.id(), principal.tenantId()),
                "复盘已提交评审");
    }

    @PostMapping("/{id}/review")
    public ApiResponse<IncidentPostmortem> review(@PathVariable Long id,
                                                   @Valid @RequestBody ReviewRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(postmortemService.review(id, request.approve(), request.comment(),
                principal.id(), principal.tenantId()), request.approve() ? "复盘已批准" : "复盘已驳回");
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<IncidentPostmortem> publish(@PathVariable Long id,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(postmortemService.publish(id, principal.id(), principal.tenantId()),
                "复盘已发布");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理重大事件复盘");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以评审和发布重大事件复盘");
        }
    }

    public record CreateRequest(@NotNull Long incidentId, @Size(max = 160) String title,
                                Long facilitatorId, Long reviewerId, LocalDateTime dueAt) {
        PostmortemService.CreateCommand command() {
            return new PostmortemService.CreateCommand(incidentId, title, facilitatorId, reviewerId, dueAt);
        }
    }

    public record UpdateRequest(@NotBlank @Size(max = 160) String title,
                                Long facilitatorId, Long reviewerId,
                                @Size(max = 10000) String executiveSummary,
                                @Size(max = 10000) String customerImpact,
                                @Size(max = 10000) String rootCause,
                                @Size(max = 10000) String contributingFactors,
                                @Size(max = 10000) String detectionGap,
                                @Size(max = 10000) String resolutionSummary,
                                @Size(max = 10000) String lessonsLearned,
                                @Size(max = 10000) String preventionPlan,
                                LocalDateTime dueAt) {
        PostmortemService.UpdateCommand command() {
            return new PostmortemService.UpdateCommand(title, facilitatorId, reviewerId, executiveSummary,
                    customerImpact, rootCause, contributingFactors, detectionGap, resolutionSummary,
                    lessonsLearned, preventionPlan, dueAt);
        }
    }

    public record ActionCreateRequest(@NotBlank @Size(max = 180) String title,
                                      @Size(max = 2000) String description, Long ownerId,
                                      PostmortemActionPriority priority, LocalDateTime dueAt) {
        PostmortemService.ActionCreateCommand command() {
            return new PostmortemService.ActionCreateCommand(title, description, ownerId, priority, dueAt);
        }
    }

    public record ActionUpdateRequest(@Size(max = 180) String title,
                                      @Size(max = 2000) String description, Long ownerId,
                                      PostmortemActionPriority priority, LocalDateTime dueAt,
                                      PostmortemActionStatus status,
                                      @Size(max = 2000) String completionEvidence) {
        PostmortemService.ActionUpdateCommand command() {
            return new PostmortemService.ActionUpdateCommand(title, description, ownerId, priority, dueAt,
                    status, completionEvidence);
        }
    }

    public record ReviewRequest(@NotNull Boolean approve, @Size(max = 1000) String comment) { }
}
