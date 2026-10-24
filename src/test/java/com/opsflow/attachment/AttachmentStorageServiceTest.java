package com.opsflow.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentStorageServiceTest {
    @Mock private AttachmentObjectStore localStore;
    @Mock private AttachmentObjectStore s3Store;

    private AttachmentStorageService storageService;

    @BeforeEach
    void setUp() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.setType(AttachmentStorageType.S3);
        properties.setPresignedDownloadTtl(Duration.ofMinutes(5));
        properties.getS3().setBucket("opsflow-test");
        when(localStore.type()).thenReturn(AttachmentStorageType.LOCAL);
        when(s3Store.type()).thenReturn(AttachmentStorageType.S3);
        storageService = new AttachmentStorageService(properties, List.of(localStore, s3Store));
        storageService.validateConfiguration();
    }

    @Test
    void newObjectsUseConfiguredBackend() {
        when(s3Store.put(anyString(), any(byte[].class), anyString(), anyMap()))
                .thenReturn(new StoredAttachmentObject("etag", "v1"));

        StoredAttachmentObject stored = storageService.put("quarantine/1/2/file.txt",
                "evidence".getBytes(), "text/plain", Map.of());

        assertEquals("v1", stored.versionId());
        verify(s3Store).put(eq("quarantine/1/2/file.txt"), any(byte[].class),
                eq("text/plain"), eq(Map.of()));
    }

    @Test
    void historicalObjectKeepsItsOriginalBackendAndSupportsPresigning() throws Exception {
        TicketAttachment attachment = new TicketAttachment();
        attachment.setStorageBackend(AttachmentStorageType.LOCAL);
        attachment.setStoragePath("clean/1/2/file.txt");
        attachment.setOriginalName("file.txt");
        attachment.setContentType("text/plain");
        when(localStore.load("clean/1/2/file.txt")).thenReturn(new ByteArrayResource("ok".getBytes()));
        when(localStore.presignDownload(eq("clean/1/2/file.txt"), eq("file.txt"),
                eq("text/plain"), eq(Duration.ofMinutes(5))))
                .thenReturn(Optional.of(URI.create("https://objects.example/file")));

        assertEquals("ok", storageService.load(attachment).getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var signed = storageService.presignDownload(attachment).orElseThrow();

        assertEquals("https://objects.example/file", signed.uri().toString());
        assertTrue(signed.expiresAt().isAfter(java.time.Instant.now()));
        verify(localStore).load("clean/1/2/file.txt");
    }

    @Test
    void rejectsTraversalBeforeCallingStorageProvider() {
        assertThrows(AttachmentStorageException.class,
                () -> storageService.put("../tenant-secret", new byte[]{1}, "text/plain", Map.of()));
    }
}
