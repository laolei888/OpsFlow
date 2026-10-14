package com.opsflow.attachment;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
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

    @Test
    void rejectsExecutableContentDisguisedAsImage() {
        ReflectionTestUtils.setField(attachmentService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(attachmentService, "maxFileSize", 10L * 1024 * 1024);
        MockMultipartFile file = new MockMultipartFile(
                "file", "evidence.jpg", "image/jpeg", new byte[]{0x4d, 0x5a, 0x00, 0x00});

        BusinessException exception = assertThrows(BusinessException.class,
                () -> attachmentService.upload(1L, 7L, 1L, 7L, file));

        assertEquals("附件实际内容与文件扩展名不匹配", exception.getMessage());
        verify(attachmentMapper, never()).insert(any(TicketAttachment.class));
    }

    @Test
    void storesVerifiedContentTypeInsteadOfClientProvidedType() {
        ReflectionTestUtils.setField(attachmentService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(attachmentService, "maxFileSize", 10L * 1024 * 1024);
        MockMultipartFile file = new MockMultipartFile(
                "file", "diagnostic.txt", "application/x-msdownload", "safe text".getBytes());
        when(attachmentMapper.insert(any(TicketAttachment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TicketAttachment.class).setId(100L);
            return 1;
        });

        TicketAttachment saved = attachmentService.upload(1L, 7L, 1L, 7L, file);

        assertEquals("text/plain", saved.getContentType());
    }
}
