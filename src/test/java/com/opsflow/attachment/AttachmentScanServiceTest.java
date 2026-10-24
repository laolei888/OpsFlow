package com.opsflow.attachment;

import com.opsflow.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentScanServiceTest {
    @Mock private TicketAttachmentMapper attachmentMapper;
    @Mock private AttachmentThreatScanner threatScanner;
    @Mock private AuditLogService auditLogService;
    @Mock private AttachmentSecurityMetrics metrics;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private AttachmentStorageService storageService;

    @TempDir Path tempDir;
    private AttachmentScanService scanService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scanService = new AttachmentScanService(attachmentMapper, threatScanner, auditLogService,
                metrics, transactionTemplate, storageService);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(storageService.materialize(any(TicketAttachment.class))).thenAnswer(invocation -> {
            TicketAttachment item = invocation.getArgument(0);
            return new MaterializedAttachment(tempDir.resolve(item.getStoragePath()), () -> { });
        });
        lenient().when(storageService.exists(any(TicketAttachment.class))).thenReturn(true);
        lenient().doAnswer(invocation -> {
            TicketAttachment item = invocation.getArgument(0);
            String targetKey = invocation.getArgument(1);
            Path source = tempDir.resolve(item.getStoragePath());
            Path target = tempDir.resolve(targetKey);
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            Files.delete(source);
            return null;
        }).when(storageService).move(any(TicketAttachment.class), any(String.class));
    }

    @Test
    void cleanScanMovesAttachmentOutOfQuarantine() throws Exception {
        TicketAttachment attachment = pendingAttachment();
        Path source = tempDir.resolve(attachment.getStoragePath());
        Files.createDirectories(source.getParent());
        Files.writeString(source, "safe evidence");
        when(attachmentMapper.selectForUpdate(10L, 1L)).thenReturn(attachment);
        when(threatScanner.scan(any(Path.class), eq(attachment))).thenReturn(
                AttachmentThreatScanner.ScanResult.clean("LOCAL_POLICY", "NO_THREAT_FOUND", "a".repeat(64)));

        scanService.scanOne(10L, 1L);

        assertEquals(AttachmentScanStatus.CLEAN, attachment.getScanStatus());
        assertEquals("clean/1/7/file.txt", attachment.getStoragePath());
        assertTrue(Files.exists(tempDir.resolve("clean/1/7/file.txt")));
        assertFalse(Files.exists(source));
        verify(metrics).clean();
    }

    @Test
    void infectedScanKeepsFileInQuarantine() throws Exception {
        TicketAttachment attachment = pendingAttachment();
        Path source = tempDir.resolve(attachment.getStoragePath());
        Files.createDirectories(source.getParent());
        Files.writeString(source, "malicious evidence");
        when(attachmentMapper.selectForUpdate(10L, 1L)).thenReturn(attachment);
        when(threatScanner.scan(any(Path.class), eq(attachment))).thenReturn(
                AttachmentThreatScanner.ScanResult.infected("LOCAL_POLICY", "EICAR_TEST_SIGNATURE", "b".repeat(64)));

        scanService.scanOne(10L, 1L);

        assertEquals(AttachmentScanStatus.INFECTED, attachment.getScanStatus());
        assertEquals("quarantine/1/7/file.txt", attachment.getStoragePath());
        assertTrue(Files.exists(source));
        verify(metrics).blocked();
    }

    @Test
    void terminalFailurePreservesHashEvidenceForManualReview() throws Exception {
        TicketAttachment attachment = pendingAttachment();
        attachment.setScanAttempts(4);
        Path source = tempDir.resolve(attachment.getStoragePath());
        Files.createDirectories(source.getParent());
        Files.writeString(source, "unavailable external scan evidence");
        when(attachmentMapper.selectForUpdate(10L, 1L)).thenReturn(attachment);
        when(threatScanner.scan(any(Path.class), eq(attachment))).thenThrow(
                new AttachmentThreatScanException("ClamAV 服务不可用", null, true, "c".repeat(64)));

        scanService.scanOne(10L, 1L);

        assertEquals(AttachmentScanStatus.FAILED, attachment.getScanStatus());
        assertEquals("c".repeat(64), attachment.getSha256());
        assertTrue(Files.exists(source));
        verify(metrics).failed();
    }

    private TicketAttachment pendingAttachment() {
        TicketAttachment attachment = new TicketAttachment();
        attachment.setId(10L);
        attachment.setTenantId(1L);
        attachment.setTicketId(7L);
        attachment.setUploaderId(9L);
        attachment.setOriginalName("file.txt");
        attachment.setStoredName("file.txt");
        attachment.setStoragePath("quarantine/1/7/file.txt");
        attachment.setStorageBackend(AttachmentStorageType.LOCAL);
        attachment.setScanStatus(AttachmentScanStatus.PENDING);
        attachment.setScanAttempts(0);
        attachment.setNextScanAt(LocalDateTime.now().minusSeconds(1));
        attachment.setCreatedAt(LocalDateTime.now());
        attachment.setUpdatedAt(LocalDateTime.now());
        return attachment;
    }
}
