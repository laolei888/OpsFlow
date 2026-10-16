package com.opsflow.messaging;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/admin/async-messages")
@RequiredArgsConstructor
public class AsyncMessageController {
    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final AsyncMessageOutboxService outboxService;
    private final DeadLetterMessageService deadLetterService;

    @GetMapping
    public ApiResponse<List<AsyncMessageOutboxService.OutboxEventView>> list(
            @RequestParam(required = false) AsyncMessageOutboxStatus status,
            @RequestParam(required = false) String notificationType,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.list(principal.tenantId(), status,
                notificationType, aggregateType, keyword, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<AsyncMessageOutboxService.OutboxSummary> summary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.summary(principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AsyncMessageOutboxService.OutboxDiagnostic> diagnostic(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.diagnostic(id, principal.tenantId()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AsyncMessageOutboxEvent> retry(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.retry(id, principal.tenantId(), principal.id()),
                "异步消息已重新排队");
    }

    @PostMapping("/retry")
    public ApiResponse<Integer> retryBatch(@Valid @RequestBody RetryBatchRequest request,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.retryBatch(request.ids(), principal.tenantId(), principal.id()),
                "选中的异步消息已重新排队");
    }

    @GetMapping("/dead-letters")
    public ApiResponse<List<DeadLetterMessageService.DeadLetterView>> deadLetters(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deadLetterService.list(principal.tenantId(), status, keyword, limit));
    }

    @GetMapping("/dead-letters/summary")
    public ApiResponse<DeadLetterMessageService.DeadLetterSummary> deadLetterSummary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deadLetterService.summary(principal.tenantId()));
    }

    @GetMapping("/dead-letters/{id}")
    public ApiResponse<DeadLetterMessageService.DeadLetterDetail> deadLetterDetail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deadLetterService.detail(id, principal.tenantId()));
    }

    @PostMapping("/dead-letters/{id}/requeue")
    public ApiResponse<DeadLetterMessage> requeueDeadLetter(
            @PathVariable Long id, @Valid @RequestBody DeadLetterOperationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deadLetterService.requeue(id, principal.tenantId(), principal.id(),
                request.expectedMessageId(), request.reason()), "死信消息已重新进入主队列");
    }

    @PostMapping("/dead-letters/{id}/discard")
    public ApiResponse<DeadLetterMessage> discardDeadLetter(
            @PathVariable Long id, @Valid @RequestBody DeadLetterOperationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deadLetterService.discard(id, principal.tenantId(), principal.id(),
                request.expectedMessageId(), request.reason()), "死信消息已作废");
    }

    @GetMapping(value = "/dead-letters/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportDeadLetters(
            @RequestParam String ids, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        List<Long> selectedIds;
        try {
            selectedIds = Arrays.stream(ids.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Long::valueOf)
                    .distinct()
                    .toList();
        } catch (NumberFormatException ex) {
            throw new BusinessException("死信消息 ID 格式不正确");
        }
        byte[] content = deadLetterService.exportCsv(principal.tenantId(), principal.id(), selectedIds);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CSV_MEDIA_TYPE);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("opsflow-dead-letters.csv", StandardCharsets.UTF_8).build());
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理异步消息");
        }
    }

    public record RetryBatchRequest(@NotEmpty @Size(max = 100) List<Long> ids) { }

    public record DeadLetterOperationRequest(
            @NotBlank @Size(max = 100) String expectedMessageId,
            @NotBlank @Size(min = 5, max = 500) String reason) { }
}
