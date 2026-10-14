package com.opsflow.ticket;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketImportController {
    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private static final byte[] TEMPLATE = ("\uFEFF标题,描述,分类,优先级\n").getBytes(StandardCharsets.UTF_8);

    private final TicketImportService ticketImportService;

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TicketImportService.ImportResult> importCsv(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(ticketImportService.importCsv(file, principal.id(), principal.tenantId()),
                "工单批量导入完成");
    }

    @GetMapping(value = "/import/template.csv", produces = "text/csv")
    public ResponseEntity<ByteArrayResource> template(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CSV_MEDIA_TYPE);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("opsflow-ticket-import-template.csv", StandardCharsets.UTF_8).build());
        headers.setContentLength(TEMPLATE.length);
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(TEMPLATE));
    }

    private void requireStaff(AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以批量导入工单");
        }
    }
}