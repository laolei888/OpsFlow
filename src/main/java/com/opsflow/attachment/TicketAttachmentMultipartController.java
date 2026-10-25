package com.opsflow.attachment;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class TicketAttachmentMultipartController {
    private final AttachmentMultipartUploadService uploadService;

    @GetMapping("/upload-capabilities")
    public ApiResponse<AttachmentStorageService.MultipartCapabilities> capabilities(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(uploadService.capabilities(ticketId, principal.tenantId(), requesterScope(principal)));
    }

    @PostMapping("/uploads")
    public ApiResponse<AttachmentMultipartUploadService.UploadSessionView> initiate(
            @PathVariable Long ticketId,
            @Valid @RequestBody InitiateUploadRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(uploadService.initiate(ticketId, principal.id(), principal.tenantId(),
                requesterScope(principal), request.clientUploadId(), request.originalName(), request.sizeBytes()),
                "分片上传会话已创建");
    }

    @GetMapping("/uploads/{sessionKey}")
    public ApiResponse<AttachmentMultipartUploadService.UploadSessionView> status(
            @PathVariable Long ticketId,
            @PathVariable String sessionKey,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(uploadService.status(ticketId, sessionKey, principal.id(), principal.tenantId(),
                requesterScope(principal)));
    }

    @PostMapping("/uploads/{sessionKey}/parts/{partNumber}/url")
    public ApiResponse<AttachmentMultipartUploadService.UploadPartAccess> partAccess(
            @PathVariable Long ticketId,
            @PathVariable String sessionKey,
            @PathVariable @Min(1) int partNumber,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(uploadService.partAccess(ticketId, sessionKey, partNumber, principal.id(),
                principal.tenantId(), requesterScope(principal)));
    }

    @PostMapping("/uploads/{sessionKey}/complete")
    public ApiResponse<TicketAttachment> complete(
            @PathVariable Long ticketId,
            @PathVariable String sessionKey,
            @Valid @RequestBody CompleteUploadRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<CompletedMultipartPart> parts = request.parts().stream()
                .map(item -> new CompletedMultipartPart(item.partNumber(), item.etag()))
                .toList();
        return ApiResponse.ok(uploadService.complete(ticketId, sessionKey, principal.id(), principal.tenantId(),
                requesterScope(principal), parts), "附件分片合并完成，正在安全扫描");
    }

    @DeleteMapping("/uploads/{sessionKey}")
    public ApiResponse<Void> abort(
            @PathVariable Long ticketId,
            @PathVariable String sessionKey,
            @AuthenticationPrincipal AuthPrincipal principal) {
        uploadService.abort(ticketId, sessionKey, principal.id(), principal.tenantId(), requesterScope(principal));
        return ApiResponse.ok(null, "分片上传已取消");
    }

    private Long requesterScope(AuthPrincipal principal) {
        return principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
    }

    public record InitiateUploadRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") String clientUploadId,
            @NotBlank @Size(max = 255) String originalName,
            @NotNull @Min(1) Long sizeBytes) { }

    public record CompleteUploadRequest(
            @NotNull @Size(max = 10_000) List<@Valid CompletedPartRequest> parts) { }

    public record CompletedPartRequest(@Min(1) int partNumber,
                                       @NotBlank @Size(max = 256) String etag) { }
}
