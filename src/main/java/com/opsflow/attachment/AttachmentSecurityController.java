package com.opsflow.attachment;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/attachment-security")
@RequiredArgsConstructor
public class AttachmentSecurityController {
    private final AttachmentScanService scanService;
    private final AttachmentStorageService storageService;
    private final AttachmentStorageGovernanceService governanceService;

    @GetMapping
    public ApiResponse<List<AttachmentScanService.AttachmentView>> list(
            @RequestParam(required = false) AttachmentScanStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(scanService.search(principal.tenantId(), status, keyword, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<AttachmentScanService.ScanSummary> summary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(scanService.summary(principal.tenantId()));
    }

    @GetMapping("/storage")
    public ApiResponse<AttachmentStorageService.StorageStatus> storage(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(storageService.status());
    }

    @GetMapping("/governance/summary")
    public ApiResponse<AttachmentStorageQuotaService.StorageUsageSummary> governanceSummary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(governanceService.summary(principal.tenantId()));
    }

    @GetMapping("/governance/findings")
    public ApiResponse<List<AttachmentStorageFinding>> governanceFindings(
            @RequestParam(required = false) AttachmentStorageFindingStatus status,
            @RequestParam(required = false) AttachmentStorageFindingType type,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(governanceService.findings(principal.tenantId(), status, type, limit));
    }

    @PostMapping("/governance/reconcile")
    public ApiResponse<AttachmentStorageGovernanceService.ReconcileResult> reconcileStorage(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(governanceService.reconcile(principal.tenantId(), principal.id()),
                "对象存储巡检已完成");
    }

    @PutMapping("/governance/policy")
    public ApiResponse<AttachmentStorageQuotaService.StorageUsageSummary> updateStoragePolicy(
            @Valid @RequestBody StoragePolicyRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(governanceService.updatePolicy(principal.tenantId(), principal.id(),
                request.quotaBytes(), request.warningPercent()), "附件存储配额策略已更新");
    }

    @PostMapping("/governance/findings/{id}/delete")
    public ApiResponse<AttachmentStorageFinding> deleteGovernedObject(
            @PathVariable Long id, @Valid @RequestBody DeleteFindingRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(governanceService.deleteFinding(id, principal.tenantId(), principal.id(),
                request.expectedObjectKey(), request.reason()), "对象版本已清理并写入审计日志");
    }

    @PostMapping("/governance/findings/{id}/ignore")
    public ApiResponse<AttachmentStorageFinding> ignoreGovernanceFinding(
            @PathVariable Long id, @Valid @RequestBody OperationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(governanceService.ignoreFinding(id, principal.tenantId(), principal.id(),
                request.reason()), "巡检记录已忽略并写入审计日志");
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AttachmentScanService.AttachmentView> retry(
            @PathVariable Long id, @Valid @RequestBody OperationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(scanService.retry(id, principal.tenantId(), principal.id(), request.reason()),
                "附件已重新进入安全扫描队列");
    }

    @PostMapping("/{id}/release")
    public ApiResponse<AttachmentScanService.AttachmentView> release(
            @PathVariable Long id, @Valid @RequestBody ReleaseRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(scanService.release(id, principal.tenantId(), principal.id(),
                request.expectedSha256(), request.reason()), "附件已由管理员人工放行");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理附件隔离区");
        }
    }

    public record OperationRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }

    public record ReleaseRequest(
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String expectedSha256,
            @NotBlank @Size(min = 5, max = 500) String reason) { }

    public record StoragePolicyRequest(@Min(1048576) long quotaBytes,
                                       @Min(1) @Max(100) int warningPercent) { }

    public record DeleteFindingRequest(
            @NotBlank @Size(max = 512) String expectedObjectKey,
            @NotBlank @Size(min = 5, max = 500) String reason) { }
}
