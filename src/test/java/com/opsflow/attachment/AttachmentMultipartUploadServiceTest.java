package com.opsflow.attachment;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentMultipartUploadServiceTest {
    @Mock private AttachmentUploadSessionMapper sessionMapper;
    @Mock private TicketAttachmentMapper attachmentMapper;
    @Mock private TicketService ticketService;
    @Mock private AuditLogService auditLogService;
    @Mock private AttachmentStorageService storageService;
    @Mock private TransactionTemplate transactionTemplate;

    private AttachmentMultipartUploadService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(status));
        lenient().doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(status);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(storageService.multipartCapabilities()).thenReturn(capabilities());
        lenient().when(storageService.configuredType()).thenReturn(AttachmentStorageType.S3);
        service = new AttachmentMultipartUploadService(sessionMapper, attachmentMapper, ticketService,
                auditLogService, storageService, transactionTemplate);
    }

    @Test
    void initiatesIdempotentS3MultipartSessionAboveSimpleUploadLimit() {
        when(storageService.createMultipartUpload(anyString(), eq("text/plain"), anyMap()))
                .thenReturn(new MultipartUploadObject("provider-upload-1"));
        when(sessionMapper.insert(any(AttachmentUploadSession.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AttachmentUploadSession.class).setId(71L);
            return 1;
        });

        var result = service.initiate(9L, 7L, 1L, 7L,
                "client_upload_123456789", "large.txt", 12L * 1024 * 1024);

        assertEquals(AttachmentUploadStatus.INITIATED, result.status());
        assertEquals(2, result.totalParts());
        assertEquals(8L * 1024 * 1024, result.partSize());
        verify(storageService).createMultipartUpload(anyString(), eq("text/plain"), anyMap());
        verify(ticketService).getVisible(9L, 1L, 7L);
    }

    @Test
    void signsOnlyPartsInsideOwnedSession() {
        AttachmentUploadSession session = session(AttachmentUploadStatus.INITIATED);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(storageService.presignUploadPart(eq(AttachmentStorageType.S3), anyString(),
                eq("provider-upload-1"), eq(2), any(Duration.class)))
                .thenReturn(new AttachmentStorageService.PresignedUploadPart(
                        URI.create("http://objects.example/part-2"), Instant.now().plusSeconds(600)));

        var result = service.partAccess(9L, session.getSessionKey(), 2, 7L, 1L, 7L);

        assertEquals(2, result.partNumber());
        assertEquals("http://objects.example/part-2", result.url());
        assertThrows(BusinessException.class,
                () -> service.partAccess(9L, session.getSessionKey(), 3, 7L, 1L, 7L));
    }

    @Test
    void completesEveryPartAndCreatesQuarantinedAttachment() {
        AttachmentUploadSession session = session(AttachmentUploadStatus.INITIATED);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.selectForUpdate(session.getSessionKey(), 1L)).thenReturn(session);
        when(storageService.exists(AttachmentStorageType.S3, session.getStoragePath())).thenReturn(false);
        when(storageService.completeMultipartUpload(eq(AttachmentStorageType.S3), eq(session.getStoragePath()),
                eq("provider-upload-1"), any())).thenReturn(new StoredAttachmentObject("etag", "v1"));
        when(storageService.metadata(AttachmentStorageType.S3, session.getStoragePath()))
                .thenReturn(new StoredObjectMetadata(session.getSizeBytes(), "etag", "v1"));
        when(attachmentMapper.insert(any(TicketAttachment.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TicketAttachment.class).setId(88L);
            return 1;
        });

        TicketAttachment result = service.complete(9L, session.getSessionKey(), 7L, 1L, 7L,
                List.of(new CompletedMultipartPart(1, "etag-1"),
                        new CompletedMultipartPart(2, "etag-2")));

        assertEquals(88L, result.getId());
        assertEquals(AttachmentScanStatus.PENDING, result.getScanStatus());
        assertEquals(AttachmentUploadStatus.COMPLETED, session.getStatus());
        assertEquals(88L, session.getAttachmentId());
        verify(storageService).completeMultipartUpload(eq(AttachmentStorageType.S3),
                eq(session.getStoragePath()), eq("provider-upload-1"), any());
    }

    @Test
    void rejectsIncompletePartManifestBeforeObjectMerge() {
        AttachmentUploadSession session = session(AttachmentUploadStatus.INITIATED);
        when(sessionMapper.selectOne(any())).thenReturn(session);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(9L, session.getSessionKey(), 7L, 1L, 7L,
                        List.of(new CompletedMultipartPart(1, "etag-1"))));

        assertTrue(exception.getMessage().contains("分片数量"));
        verify(storageService, never()).completeMultipartUpload(any(), anyString(), anyString(), any());
    }

    @Test
    void rejectsASecondCompletionWhileTheFirstRequestOwnsTheLease() {
        AttachmentUploadSession session = session(AttachmentUploadStatus.COMPLETING);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.selectForUpdate(session.getSessionKey(), 1L)).thenReturn(session);
        when(storageService.exists(AttachmentStorageType.S3, session.getStoragePath())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.complete(9L, session.getSessionKey(), 7L, 1L, 7L,
                        List.of(new CompletedMultipartPart(1, "etag-1"),
                                new CompletedMultipartPart(2, "etag-2"))));

        assertTrue(exception.getMessage().contains("正在合并"));
        verify(storageService, never()).completeMultipartUpload(any(), anyString(), anyString(), any());
    }

    private AttachmentUploadSession session(AttachmentUploadStatus status) {
        AttachmentUploadSession session = new AttachmentUploadSession();
        session.setId(71L);
        session.setSessionKey("12345678-1234-1234-1234-123456789012");
        session.setClientUploadId("client_upload_123456789");
        session.setTenantId(1L);
        session.setTicketId(9L);
        session.setUploaderId(7L);
        session.setOriginalName("large.txt");
        session.setStoredName("stored.txt");
        session.setContentType("text/plain");
        session.setSizeBytes(12L * 1024 * 1024);
        session.setStorageBackend(AttachmentStorageType.S3);
        session.setStoragePath("quarantine/1/9/stored.txt");
        session.setProviderUploadId("provider-upload-1");
        session.setStatus(status);
        session.setPartSize(8L * 1024 * 1024);
        session.setTotalParts(2);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private AttachmentStorageService.MultipartCapabilities capabilities() {
        return new AttachmentStorageService.MultipartCapabilities(true, 10L * 1024 * 1024,
                1024L * 1024 * 1024, 8L * 1024 * 1024,
                Duration.ofHours(24), Duration.ofMinutes(15));
    }
}
