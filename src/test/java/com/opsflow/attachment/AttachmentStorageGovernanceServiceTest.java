package com.opsflow.attachment;

import com.opsflow.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentStorageGovernanceServiceTest {
    @Mock private AttachmentStorageFindingMapper findingMapper;
    @Mock private AttachmentStoragePolicyMapper policyMapper;
    @Mock private TicketAttachmentMapper attachmentMapper;
    @Mock private AttachmentUploadSessionMapper sessionMapper;
    @Mock private AttachmentStorageService storageService;
    @Mock private AttachmentStorageQuotaService quotaService;
    @Mock private AuditLogService auditLogService;
    @Mock private TransactionTemplate transactionTemplate;

    private AttachmentStorageGovernanceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.getGovernance().setOrphanGracePeriod(Duration.ofHours(1));
        service = new AttachmentStorageGovernanceService(findingMapper, policyMapper, attachmentMapper,
                sessionMapper, storageService, properties, quotaService, auditLogService, transactionTemplate);
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(status));
        lenient().doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(status);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void reconcileFindsOrphanAndNoncurrentVersionWithoutDeletingAnything() {
        TicketAttachment reference = new TicketAttachment();
        reference.setStorageBackend(AttachmentStorageType.S3);
        reference.setStoragePath("clean/1/7/kept.txt");
        reference.setStorageVersion("v-current");
        reference.setSizeBytes(12L);
        when(attachmentMapper.selectList(any())).thenReturn(List.of(reference));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(storageService.inventoryBackends()).thenReturn(List.of(AttachmentStorageType.S3));
        when(storageService.inventory(eq(AttachmentStorageType.S3), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String prefix = invocation.getArgument(1);
                    if (prefix.startsWith("quarantine")) {
                        return new StoredObjectInventory(List.of(new StoredObjectInventoryItem(
                                "quarantine/1/7/orphan.txt", 8L, Instant.now().minusSeconds(7200),
                                "orphan-etag", "v1", true, false)), false);
                    }
                    return new StoredObjectInventory(List.of(
                            new StoredObjectInventoryItem("clean/1/7/kept.txt", 12L,
                                    Instant.now(), "current", "v-current", true, false),
                            new StoredObjectInventoryItem("clean/1/7/kept.txt", 10L,
                                    Instant.now().minusSeconds(7200), "old", "v-old", false, false)), false);
                });

        var result = service.reconcile(1L, 7L);

        assertEquals(2, result.detected());
        assertEquals(0, result.missing());
        ArgumentCaptor<AttachmentStorageFinding> findings =
                ArgumentCaptor.forClass(AttachmentStorageFinding.class);
        verify(findingMapper, org.mockito.Mockito.times(2)).upsertDetected(findings.capture());
        assertTrue(findings.getAllValues().stream().anyMatch(
                item -> item.getFindingType() == AttachmentStorageFindingType.ORPHAN_OBJECT));
        assertTrue(findings.getAllValues().stream().anyMatch(
                item -> item.getFindingType() == AttachmentStorageFindingType.NONCURRENT_VERSION));
    }

    @Test
    void confirmedEligibleOrphanCanBeDeletedAfterReferenceRecheck() {
        AttachmentStorageFinding finding = new AttachmentStorageFinding();
        finding.setId(11L);
        finding.setTenantId(1L);
        finding.setStorageBackend(AttachmentStorageType.S3);
        finding.setFindingType(AttachmentStorageFindingType.ORPHAN_OBJECT);
        finding.setStatus(AttachmentStorageFindingStatus.OPEN);
        finding.setObjectKey("quarantine/1/7/orphan.txt");
        finding.setVersionId("v1");
        finding.setSizeBytes(8L);
        finding.setLatestVersion(true);
        finding.setEligibleDeleteAt(LocalDateTime.now().minusMinutes(1));
        when(findingMapper.selectForUpdate(11L, 1L)).thenReturn(finding);
        when(attachmentMapper.selectList(any())).thenReturn(List.of());
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        AttachmentStorageFinding result = service.deleteFinding(11L, 1L, 7L,
                finding.getObjectKey(), "已确认对象没有业务引用");

        assertEquals(AttachmentStorageFindingStatus.DELETED, result.getStatus());
        verify(storageService).deleteVersion(AttachmentStorageType.S3, finding.getObjectKey(), "v1");
        verify(auditLogService).record(eq(1L), eq(7L), eq("ATTACHMENT_STORAGE_FINDING"),
                eq(11L), eq("ATTACHMENT_STORAGE_OBJECT_DELETED"), any(), any(), anyString());
    }
}
