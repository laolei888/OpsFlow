package com.opsflow.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentMultipartCleanupJobTest {
    @Mock private AttachmentUploadSessionMapper sessionMapper;
    @Mock private AttachmentMultipartUploadService uploadService;

    @Test
    void expiresProviderUploadsAndRemovesRetainedTerminalRecords() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.getMultipart().setCompletedRetentionDays(7);
        AttachmentMultipartCleanupJob job = new AttachmentMultipartCleanupJob(
                sessionMapper, uploadService, properties);
        AttachmentUploadSession session = session("12345678-1234-1234-1234-123456789012");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(sessionMapper.delete(any())).thenReturn(3);

        var result = job.cleanup(LocalDateTime.now());

        assertEquals(1, result.expired());
        assertEquals(3, result.removedRecords());
        verify(uploadService).expireForCleanup(session.getSessionKey(), 1L);
    }

    @Test
    void continuesWhenOneProviderCleanupFails() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        AttachmentMultipartCleanupJob job = new AttachmentMultipartCleanupJob(
                sessionMapper, uploadService, properties);
        AttachmentUploadSession first = session("12345678-1234-1234-1234-123456789012");
        AttachmentUploadSession second = session("22345678-1234-1234-1234-123456789012");
        when(sessionMapper.selectList(any())).thenReturn(List.of(first, second));
        when(sessionMapper.delete(any())).thenReturn(0);
        doThrow(new AttachmentStorageException("temporary failure"))
                .when(uploadService).expireForCleanup(first.getSessionKey(), 1L);

        var result = job.cleanup(LocalDateTime.now());

        assertEquals(1, result.expired());
        verify(uploadService).expireForCleanup(second.getSessionKey(), 1L);
    }

    private AttachmentUploadSession session(String sessionKey) {
        AttachmentUploadSession session = new AttachmentUploadSession();
        session.setSessionKey(sessionKey);
        session.setTenantId(1L);
        return session;
    }
}
