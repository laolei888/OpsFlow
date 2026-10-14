package com.opsflow.problem;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/problems")
@RequiredArgsConstructor
public class ProblemController {
    private final ProblemService problemService;

    @GetMapping
    public ApiResponse<List<Problem>> list(@RequestParam(required = false) ProblemStatus status,
                                           @RequestParam(required = false) ProblemPriority priority,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "100") Integer limit,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(problemService.list(principal.tenantId(), requesterScope(principal), status, priority,
                keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProblemService.ProblemDetail> detail(@PathVariable Long id,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(problemService.detail(id, principal.tenantId(), requesterScope(principal)));
    }

    @PostMapping
    public ApiResponse<Problem> create(@Valid @RequestBody ProblemRequest request,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(problemService.create(request.command(), idempotencyKey, principal.id(), principal.tenantId()),
                "问题单已提交");
    }

    @PutMapping("/{id}")
    public ApiResponse<Problem> update(@PathVariable Long id,
                                       @Valid @RequestBody ProblemUpdateRequest request,
                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(problemService.update(id, request.command(), principal.id(), principal.tenantId()),
                "问题分析资料已更新");
    }

    @PostMapping("/{id}/transition")
    public ApiResponse<Problem> transition(@PathVariable Long id,
                                           @RequestParam ProblemStatus target,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(problemService.transition(id, target, principal.id(), principal.tenantId()),
                "问题单状态已更新");
    }

    @PostMapping("/{id}/tasks")
    public ApiResponse<ProblemTask> createTask(@PathVariable Long id,
                                                @Valid @RequestBody TaskRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(problemService.createTask(id, request.command(), principal.tenantId(), principal.id()),
                "问题分析任务已添加");
    }

    @PutMapping("/tasks/{taskId}")
    public ApiResponse<ProblemTask> updateTask(@PathVariable Long taskId,
                                                @Valid @RequestBody TaskUpdateRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(problemService.updateTask(taskId, request.command(), principal.tenantId(), principal.id()),
                "问题分析任务已更新");
    }

    @PostMapping("/{id}/tickets")
    public ApiResponse<ProblemService.ProblemTicketView> linkTicket(
            @PathVariable Long id, @Valid @RequestBody LinkRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(problemService.linkTicket(id, request.command(), principal.tenantId(), principal.id()),
                "症状工单已关联");
    }

    @DeleteMapping("/{id}/tickets/{ticketId}")
    public ApiResponse<Void> unlinkTicket(@PathVariable Long id, @PathVariable Long ticketId,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        problemService.unlinkTicket(id, ticketId, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "问题工单关联已解除");
    }

    @PostMapping("/{id}/changes")
    public ApiResponse<ProblemService.ProblemChangeView> linkChange(
            @PathVariable Long id, @Valid @RequestBody LinkRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(problemService.linkChange(id, request.command(), principal.tenantId(), principal.id()),
                "修复变更已关联");
    }

    @DeleteMapping("/{id}/changes/{changeId}")
    public ApiResponse<Void> unlinkChange(@PathVariable Long id, @PathVariable Long changeId,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        problemService.unlinkChange(id, changeId, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "问题变更关联已解除");
    }

    private Long requesterScope(AuthPrincipal principal) {
        return principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以维护问题分析流程");
        }
    }

    public record ProblemRequest(@NotBlank @Size(max = 160) String title,
                                 @NotBlank @Size(max = 10000) String description,
                                 @Size(max = 1000) String businessImpact,
                                 @NotNull ProblemPriority priority,
                                 @Size(max = 10000) String workaround,
                                 Boolean knownError,
                                 Long ownerId,
                                 @Size(max = 30) List<@Size(max = 160) String> taskTitles) {
        ProblemService.ProblemCommand command() {
            return new ProblemService.ProblemCommand(title, description, businessImpact, priority, workaround,
                    knownError, ownerId, taskTitles);
        }
    }

    public record ProblemUpdateRequest(ProblemPriority priority,
                                       @Size(max = 10000) String rootCause,
                                       @Size(max = 10000) String workaround,
                                       @Size(max = 10000) String resolution,
                                       Boolean knownError,
                                       Long ownerId) {
        ProblemService.ProblemUpdateCommand command() {
            return new ProblemService.ProblemUpdateCommand(priority, rootCause, workaround, resolution,
                    knownError, ownerId);
        }
    }

    public record TaskRequest(@NotBlank @Size(max = 160) String title,
                              Long assigneeId, @Min(0) @Max(9999) Integer sortOrder) {
        ProblemService.TaskCommand command() {
            return new ProblemService.TaskCommand(title, assigneeId, sortOrder);
        }
    }

    public record TaskUpdateRequest(@Size(max = 160) String title,
                                    Long assigneeId, ProblemTaskStatus status) {
        ProblemService.TaskUpdateCommand command() {
            return new ProblemService.TaskUpdateCommand(title, assigneeId, status);
        }
    }

    public record LinkRequest(@NotNull Long targetId, @Size(max = 32) String linkType,
                              @Size(max = 500) String note) {
        ProblemService.LinkCommand command() {
            return new ProblemService.LinkCommand(targetId, linkType, note);
        }
    }
}
