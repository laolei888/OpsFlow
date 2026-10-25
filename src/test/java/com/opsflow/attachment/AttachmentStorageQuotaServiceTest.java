package com.opsflow.attachment;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentStorageQuotaServiceTest {
    @Mock private AttachmentStoragePolicyMapper policyMapper;
    @Mock private AttachmentStorageFindingMapper findingMapper;
    @Mock private TicketAttachmentMapper attachmentMapper;
    @Mock private AttachmentUploadSessionMapper sessionMapper;
    @Mock private AuditLogService auditLogService;

    private AttachmentStorageQuotaService service;
    private AttachmentStoragePolicy policy;

    @BeforeEach
    void setUp() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.getGovernance().setDefaultQuotaBytes(100L * 1024 * 1024);
        service = new AttachmentStorageQuotaService(policyMapper, findingMapper, attachmentMapper,
                sessionMapper, properties, auditLogService);
        policy = new AttachmentStoragePolicy();
        policy.setTenantId(1L);
        policy.setQuotaBytes(100L * 1024 * 1024);
        policy.setWarningPercent(80);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        when(policyMapper.selectForUpdate(1L)).thenReturn(policy);
    }

    @Test
    void rejectsUploadWhenUsedAndReservedBytesWouldExceedQuota() {
        when(attachmentMapper.sumSizeByTenant(1L)).thenReturn(70L * 1024 * 1024);
        when(sessionMapper.sumReservedSizeByTenant(1L)).thenReturn(20L * 1024 * 1024);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.assertCanStore(1L, 11L * 1024 * 1024));

        assertEquals(true, exception.getMessage().contains("配额不足"));
        verify(policyMapper).insertDefault(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void acceptsUploadWithinQuota() {
        when(attachmentMapper.sumSizeByTenant(1L)).thenReturn(40L * 1024 * 1024);
        when(sessionMapper.sumReservedSizeByTenant(1L)).thenReturn(10L * 1024 * 1024);

        service.assertCanStore(1L, 25L * 1024 * 1024);

        verify(policyMapper).selectForUpdate(1L);
    }

    @Test
    void refusesToLowerPolicyBelowOccupiedSpace() {
        when(attachmentMapper.sumSizeByTenant(1L)).thenReturn(70L * 1024 * 1024);
        when(sessionMapper.sumReservedSizeByTenant(1L)).thenReturn(20L * 1024 * 1024);

        assertThrows(BusinessException.class,
                () -> service.updatePolicy(1L, 7L, 80L * 1024 * 1024, 75));
    }
}
