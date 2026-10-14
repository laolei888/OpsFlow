package com.opsflow.attachment;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class TicketAttachmentController {
    private final TicketAttachmentService attachmentService;

    @GetMapping
    public ApiResponse<List<TicketAttachment>> list(@PathVariable Long ticketId,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(attachmentService.list(ticketId, principal.tenantId(), requesterScope(principal)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TicketAttachment> upload(@PathVariable Long ticketId,
                                                @RequestPart("file") MultipartFile file,
                                                @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(attachmentService.upload(ticketId, principal.id(), principal.tenantId(), requesterScope(principal), file),
                "附件上传成功");
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long ticketId,
                                             @PathVariable Long attachmentId,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        TicketAttachmentService.DownloadedAttachment downloaded = attachmentService.download(
                ticketId, attachmentId, principal.tenantId(), requesterScope(principal));
        TicketAttachment attachment = downloaded.attachment();
        MediaType mediaType = MediaTypeFactory.getMediaType(attachment.getOriginalName())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        String filename = attachment.getOriginalName();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.getSizeBytes())
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(downloaded.resource());
    }

    @DeleteMapping("/{attachmentId}")
    public ApiResponse<Void> delete(@PathVariable Long ticketId,
                                    @PathVariable Long attachmentId,
                                    @AuthenticationPrincipal AuthPrincipal principal) {
        boolean staff = principal.hasAnyRole("AGENT", "ADMIN");
        attachmentService.delete(ticketId, attachmentId, principal.id(), principal.tenantId(),
                staff, staff ? null : principal.id());
        return ApiResponse.ok(null, "附件已删除");
    }

    private Long requesterScope(AuthPrincipal principal) {
        return principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
    }
}