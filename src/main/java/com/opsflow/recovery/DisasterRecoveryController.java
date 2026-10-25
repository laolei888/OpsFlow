package com.opsflow.recovery;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/disaster-recovery")
@RequiredArgsConstructor
public class DisasterRecoveryController {
    private final DisasterRecoveryService service;
    private final BackupAgentService backupAgentService;

    @GetMapping
    public ApiResponse<DisasterRecoveryService.RecoveryCenterView> center(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.center(principal.tenantId()));
    }

    @PutMapping("/policy")
    public ApiResponse<DisasterRecoveryService.EffectivePolicy> savePolicy(
            @Valid @RequestBody PolicyRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.savePolicy(request.command(), principal.tenantId(), principal.id()),
                "灾备策略已更新");
    }

    @PostMapping("/backups")
    public ApiResponse<BackupSnapshot> registerBackup(
            @Valid @RequestBody BackupRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.registerBackup(request.command(), principal.tenantId(), principal.id()),
                "备份已登记并完成策略校验");
    }

    @PostMapping("/backups/{id}/verify")
    public ApiResponse<BackupSnapshot> verifyBackup(@PathVariable Long id,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.verifyBackup(id, principal.tenantId(), principal.id()),
                "备份校验已完成");
    }

    @GetMapping("/agents")
    public ApiResponse<List<BackupAgent>> agents(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(backupAgentService.list(principal.tenantId()));
    }

    @PostMapping("/agents")
    public ApiResponse<BackupAgentService.AgentCredential> createAgent(
            @Valid @RequestBody AgentRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(backupAgentService.create(request.command(), principal.tenantId(), principal.id()),
                "备份 Agent 已创建，请立即保存一次性密钥");
    }

    @PostMapping("/agents/{id}/rotate")
    public ApiResponse<BackupAgentService.AgentCredential> rotateAgent(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(backupAgentService.rotate(id, principal.tenantId(), principal.id()),
                "签名密钥已轮换，旧密钥立即失效");
    }

    @PutMapping("/agents/{id}/status")
    public ApiResponse<BackupAgent> setAgentStatus(
            @PathVariable Long id, @Valid @RequestBody AgentStatusRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(backupAgentService.setEnabled(id, request.enabled(), principal.tenantId(), principal.id()),
                request.enabled() ? "备份 Agent 已启用" : "备份 Agent 已停用");
    }

    @GetMapping("/agents/{id}/callbacks")
    public ApiResponse<List<BackupAgentCallback>> agentCallbacks(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(backupAgentService.callbacks(id, principal.tenantId()));
    }

    @PostMapping("/drills")
    public ApiResponse<DisasterRecoveryService.DrillDetail> createDrill(
            @Valid @RequestBody DrillRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.createDrill(request.command(), principal.tenantId(), principal.id()),
                "恢复演练已创建并生成门禁清单");
    }

    @GetMapping("/drills/{id}")
    public ApiResponse<DisasterRecoveryService.DrillDetail> drillDetail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.drillDetail(id, principal.tenantId()));
    }

    @PostMapping("/drills/{id}/start")
    public ApiResponse<DisasterRecoveryService.DrillDetail> startDrill(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.startDrill(id, principal.tenantId(), principal.id()),
                "恢复演练已开始计时");
    }

    @PutMapping("/drills/{drillId}/checks/{checkId}")
    public ApiResponse<RecoveryDrillCheck> updateCheck(
            @PathVariable Long drillId, @PathVariable Long checkId,
            @Valid @RequestBody CheckRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.updateCheck(drillId, checkId, request.command(),
                principal.tenantId(), principal.id()), "演练检查证据已记录");
    }

    @PostMapping("/drills/{id}/complete")
    public ApiResponse<DisasterRecoveryService.DrillDetail> completeDrill(
            @PathVariable Long id, @Valid @RequestBody CompleteRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.completeDrill(id, request.command(), principal.tenantId(), principal.id()),
                "恢复演练已完成并计算 RPO/RTO 结果");
    }

    @PostMapping("/drills/{id}/cancel")
    public ApiResponse<RecoveryDrill> cancelDrill(
            @PathVariable Long id, @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(service.cancelDrill(id, request.reason(), principal.tenantId(), principal.id()),
                "恢复演练已取消");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理备份与恢复演练");
        }
    }

    public record PolicyRequest(@NotNull @Min(5) @Max(10080) Integer rpoMinutes,
                                @NotNull @Min(5) @Max(10080) Integer rtoMinutes,
                                @NotNull @Min(1) @Max(3650) Integer retentionDays,
                                @NotNull @Min(1) @Max(720) Integer verificationIntervalHours,
                                @NotNull Boolean requireEncryption,
                                @NotNull Boolean requireImmutableCopy,
                                @NotNull Boolean requireCrossRegionCopy,
                                @NotNull Boolean enabled) {
        DisasterRecoveryService.PolicyCommand command() {
            return new DisasterRecoveryService.PolicyCommand(rpoMinutes, rtoMinutes, retentionDays,
                    verificationIntervalHours, requireEncryption, requireImmutableCopy,
                    requireCrossRegionCopy, enabled);
        }
    }

    public record BackupRequest(@NotNull BackupScope scope,
                                @NotBlank @Size(max = 64) String storageProvider,
                                @NotBlank @Size(max = 500) String storageLocation,
                                @NotBlank @Size(max = 64) String region,
                                @NotNull LocalDateTime recoveryPointAt,
                                @NotNull LocalDateTime capturedAt,
                                @NotNull LocalDateTime completedAt,
                                @NotNull @Positive Long sizeBytes,
                                @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String checksumSha256,
                                @NotNull Boolean encrypted,
                                LocalDateTime immutableUntil,
                                @NotNull Boolean crossRegionCopy,
                                @Size(max = 1000) String evidenceNote) {
        DisasterRecoveryService.BackupCommand command() {
            return new DisasterRecoveryService.BackupCommand(scope, storageProvider, storageLocation,
                    region, recoveryPointAt, capturedAt, completedAt, sizeBytes, checksumSha256,
                    encrypted, immutableUntil, crossRegionCopy, evidenceNote);
        }
    }

    public record DrillRequest(@NotBlank @Size(min = 2, max = 160) String name,
                               @NotNull Long backupSnapshotId,
                               @NotBlank @Size(min = 2, max = 64) String environment,
                               LocalDateTime scheduledAt) {
        DisasterRecoveryService.DrillCommand command() {
            return new DisasterRecoveryService.DrillCommand(name, backupSnapshotId, environment, scheduledAt);
        }
    }

    public record CheckRequest(@NotNull RecoveryCheckStatus status,
                               @Size(max = 1000) String detail,
                               @NotBlank @Size(min = 5, max = 1000) String evidence) {
        DisasterRecoveryService.CheckCommand command() {
            return new DisasterRecoveryService.CheckCommand(status, detail, evidence);
        }
    }

    public record CompleteRequest(@NotBlank @Size(min = 5, max = 2000) String evidenceNote,
                                  @Size(max = 2000) String findings) {
        DisasterRecoveryService.CompleteCommand command() {
            return new DisasterRecoveryService.CompleteCommand(evidenceNote, findings);
        }
    }

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 1000) String reason) { }

    public record AgentRequest(@NotBlank @Size(min = 2, max = 120) String name,
                               @Size(max = 500) String description,
                               Boolean enabled) {
        BackupAgentService.AgentCommand command() {
            return new BackupAgentService.AgentCommand(name, description, enabled);
        }
    }

    public record AgentStatusRequest(@NotNull Boolean enabled) { }
}
