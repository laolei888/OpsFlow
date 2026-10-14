package com.opsflow.attachment;

import com.opsflow.audit.AuditLogService;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketAttachmentServiceTest {
    @Mock
    private TicketAttachmentMapper attachmentMapper;
    @Mock
    private TicketService ticketService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TicketAttachmentService attachmentService;

    @TempDir
    Path tempDir;

    @Test
    void uploadPassesRequesterScopeToTicketVisibilityCheck() {
        ReflectionTestUtils.setField(attachmentService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(attachmentService, "maxFileSize", 10L * 1024 * 1024);
        MockMultipartFile file = new MockMultipartFile(
                "file", "diagnostic.txt", "text/plain", "diagnostic".getBytes());
        when(attachmentMapper.insert(any(TicketAttachment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TicketAttachment.class).setId(99L);
            return 1;
        });

        attachmentService.upload(1L, 7L, 1L, 7L, file);

        verify(ticketService).getVisible(1L, 1L, 7L);
        verify(attachmentMapper).insert(any(TicketAttachment.class));
    }
}