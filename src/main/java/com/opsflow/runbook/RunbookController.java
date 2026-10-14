package com.opsflow.runbook;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.alert.AlertSeverity;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runbooks")
@RequiredArgsConstructor
public class RunbookController {
    private final RunbookService runbookService;

    @GetMapping
    public ApiResponse<RunbookService.RunbookCenterView> center(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.center(principal.tenantId()));
    }

    @GetMapping("/recommendations/alerts/{alertId}")
    public ApiResponse<List<RunbookService.AlertRecommendation>> alertRecommendations(@PathVariable Long alertId,
                                                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.recommendationsForAlert(alertId, principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<RunbookService.RunbookDetail> detail(@PathVariable Long id,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.detail(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<Runbook> create(@Valid @RequestBody RunbookRequest request,
                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(runbookService.create(request.command(), principal.tenantId(), principal.id()),
                "运行手册已创建，后续处置会固化版本快照");
    }

    @PutMapping("/{id}")
    public ApiResponse<Runbook> update(@PathVariable Long id, @Valid @RequestBody RunbookRequest request,
                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(runbookService.update(id, request.command(), principal.tenantId(), principal.id()),
                "运行手册已更新，历史处置证据保持不变");
    }

    @PostMapping("/{id}/steps")
    public ApiResponse<RunbookStep> addStep(@PathVariable Long id, @Valid @RequestBody TemplateStepRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(runbookService.addTemplateStep(id, request.command(), principal.tenantId(), principal.id()),
                "运行手册步骤已添加");
    }

    @PutMapping("/steps/{stepId}")
    public ApiResponse<RunbookStep> updateStep(@PathVariable Long stepId, @Valid @RequestBody TemplateStepRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(runbookService.updateTemplateStep(stepId, request.command(), principal.tenantId(), principal.id()),
                "运行手册模板步骤已更新");
    }

    @PostMapping("/{id}/alert-matchers")
    public ApiResponse<RunbookAlertMatcher> createAlertMatcher(@PathVariable Long id,
                                                                @Valid @RequestBody AlertMatcherRequest request,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(runbookService.createAlertMatcher(id, request.command(), principal.tenantId(), principal.id()),
                "告警推荐规则已创建；平台只会建议，仍需人工确认发起处置");
    }

    @PutMapping("/alert-matchers/{matcherId}")
    public ApiResponse<RunbookAlertMatcher> updateAlertMatcher(@PathVariable Long matcherId,
                                                                @Valid @RequestBody AlertMatcherRequest request,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(runbookService.updateAlertMatcher(matcherId, request.command(), principal.tenantId(), principal.id()),
                "告警推荐规则已更新");
    }

    @PostMapping("/executions")
    public ApiResponse<RunbookExecution> start(@Valid @RequestBody ExecutionStartRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.startExecution(request.command(), principal.tenantId(), principal.id()),
                "运行手册处置已开始，步骤已按当前版本快照固化");
    }

    @GetMapping("/executions/{id}")
    public ApiResponse<RunbookService.ExecutionDetail> executionDetail(@PathVariable Long id,
                                                                         @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.executionDetail(id, principal.tenantId()));
    }

    @PutMapping("/executions/steps/{stepId}")
    public ApiResponse<RunbookExecutionStep> updateExecutionStep(@PathVariable Long stepId,
                                                                   @Valid @RequestBody ExecutionStepRequest request,
                                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.updateExecutionStep(stepId, request.command(), principal.tenantId(), principal.id()),
                "步骤处置证据已记录");
    }

    @PostMapping("/executions/{id}/complete")
    public ApiResponse<RunbookExecution> complete(@PathVariable Long id, @RequestBody(required = false) OutcomeRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.complete(id, request == null ? null : request.note(), principal.tenantId(), principal.id()),
                "运行手册处置已完成");
    }

    @PostMapping("/executions/{id}/fail")
    public ApiResponse<RunbookExecution> fail(@PathVariable Long id, @Valid @RequestBody OutcomeRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.fail(id, request.note(), principal.tenantId(), principal.id()),
                "运行手册处置已标记失败");
    }

    @PostMapping("/executions/{id}/cancel")
    public ApiResponse<RunbookExecution> cancel(@PathVariable Long id, @Valid @RequestBody OutcomeRequest request,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(runbookService.cancel(id, request.note(), principal.tenantId(), principal.id()),
                "运行手册处置已取消");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以执行运行手册");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置运行手册模板");
        }
    }

    public record RunbookRequest(@NotBlank @Size(max = 120) String name,
                                 @NotBlank @Size(max = 140) String slug,
                                 @Size(max = 10000) String description,
                                 @NotBlank @Size(max = 64) String category,
                                 @NotNull RunbookTargetType targetType,
                                 Boolean enabled,
                                 @Size(max = 60) List<@Valid TemplateStepRequest> steps) {
        RunbookService.RunbookCommand command() {
            return new RunbookService.RunbookCommand(name, slug, description, category, targetType, enabled,
                    steps == null ? null : steps.stream().map(TemplateStepRequest::command).toList());
        }
    }

    public record TemplateStepRequest(@Max(100000) Integer sortOrder,
                                      @NotBlank @Size(max = 180) String title,
                                      @NotBlank @Size(max = 10000) String instruction,
                                      @Size(max = 1000) String verificationHint,
                                      Boolean requiredStep) {
        RunbookService.TemplateStepCommand command() {
            return new RunbookService.TemplateStepCommand(sortOrder, title, instruction, verificationHint, requiredStep);
        }
    }

    public record AlertMatcherRequest(@NotBlank @Size(max = 120) String name,
                                      Long alertSourceId,
                                      AlertSeverity minimumSeverity,
                                      @Size(max = 160) String titleKeyword,
                                      @Max(100000) Integer sortOrder,
                                      Boolean enabled) {
        RunbookService.AlertMatcherCommand command() {
            return new RunbookService.AlertMatcherCommand(name, alertSourceId, minimumSeverity, titleKeyword, sortOrder, enabled);
        }
    }

    public record ExecutionStartRequest(@NotNull Long runbookId,
                                        @NotNull RunbookExecutionSource sourceType,
                                        Long sourceId,
                                        @Size(max = 180) String title) {
        RunbookService.ExecutionStartCommand command() {
            return new RunbookService.ExecutionStartCommand(runbookId, sourceType, sourceId, title);
        }
    }

    public record ExecutionStepRequest(@NotNull RunbookStepStatus status, @Size(max = 2000) String evidence) {
        RunbookService.ExecutionStepCommand command() {
            return new RunbookService.ExecutionStepCommand(status, evidence);
        }
    }

    public record OutcomeRequest(@NotBlank @Size(max = 1000) String note) { }
}
