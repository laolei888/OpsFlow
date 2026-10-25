package com.opsflow.chaos;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/chaos-experiments")
@RequiredArgsConstructor
public class ChaosExperimentController {
    private final ChaosExperimentService service;

    @GetMapping
    public ApiResponse<ChaosExperimentService.ChaosCenterView> center(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.center(principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<ChaosExperimentService.ExperimentDetail> create(
            @Valid @RequestBody CreateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.create(request.command(), principal.tenantId(), principal.id()),
                "混沌实验已创建并生成阶段门禁");
    }

    @GetMapping("/{id}")
    public ApiResponse<ChaosExperimentService.ExperimentDetail> detail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.detail(id, principal.tenantId()));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<ChaosExperimentService.ExperimentDetail> start(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.start(id, principal.tenantId(), principal.id()), "基线采集已开始");
    }

    @PostMapping("/{id}/fault-injected")
    public ApiResponse<ChaosExperimentService.ExperimentDetail> faultInjected(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.confirmFaultInjected(id, principal.tenantId(), principal.id()),
                "已进入故障观察阶段");
    }

    @PostMapping("/{id}/recovery")
    public ApiResponse<ChaosExperimentService.ExperimentDetail> recovery(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.beginRecovery(id, principal.tenantId(), principal.id()),
                "已进入恢复验证阶段");
    }

    @PutMapping("/{experimentId}/checks/{checkId}")
    public ApiResponse<ChaosExperimentCheck> check(
            @PathVariable Long experimentId, @PathVariable Long checkId,
            @Valid @RequestBody CheckRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.updateCheck(experimentId, checkId, request.command(),
                principal.tenantId(), principal.id()), "实验检查证据已记录");
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<ChaosExperimentService.ExperimentDetail> complete(
            @PathVariable Long id, @Valid @RequestBody CompleteRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.complete(id, new ChaosExperimentService.CompleteCommand(request.findings()),
                principal.tenantId(), principal.id()), "实验已完成并计算恢复结果");
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ChaosExperiment> cancel(
            @PathVariable Long id, @Valid @RequestBody CancelRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.cancel(id, request.reason(), principal.tenantId(), principal.id()),
                "实验已取消");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理混沌实验");
        }
    }

    public record CreateRequest(@NotBlank @Size(min = 2, max = 160) String name,
                                @NotNull ChaosTarget target,
                                @NotBlank @Size(min = 2, max = 64) String environment,
                                @NotBlank @Size(max = 1000) String hypothesis,
                                @NotBlank @Size(max = 1000) String rollbackPlan,
                                @NotNull @Min(1) @Max(240) Integer targetRecoveryMinutes) {
        ChaosExperimentService.CreateCommand command() {
            return new ChaosExperimentService.CreateCommand(name, target, environment, hypothesis,
                    rollbackPlan, targetRecoveryMinutes);
        }
    }

    public record CheckRequest(@NotNull ChaosCheckStatus status,
                               @Size(max = 1000) String detail,
                               @NotBlank @Size(min = 5, max = 1000) String evidence) {
        ChaosExperimentService.CheckCommand command() {
            return new ChaosExperimentService.CheckCommand(status, detail, evidence);
        }
    }

    public record CompleteRequest(@NotBlank @Size(min = 5, max = 2000) String findings) { }
    public record CancelRequest(@NotBlank @Size(min = 5, max = 1000) String reason) { }
}
