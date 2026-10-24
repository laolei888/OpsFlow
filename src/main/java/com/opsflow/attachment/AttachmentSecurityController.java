package com.opsflow.attachment;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/attachment-security")
@RequiredArgsConstructor
public class AttachmentSecurityController {
    private final AttachmentScanService scanService;
    private final AttachmentStorageService storageService;

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
}
