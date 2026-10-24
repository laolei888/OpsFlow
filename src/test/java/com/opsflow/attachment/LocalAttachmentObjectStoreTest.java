package com.opsflow.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAttachmentObjectStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void supportsAtomicLifecycleWithinConfiguredRoot() throws Exception {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.setUploadDir(tempDir.toString());
        LocalAttachmentObjectStore store = new LocalAttachmentObjectStore(properties);
        byte[] content = "safe evidence".getBytes(StandardCharsets.UTF_8);

        StoredAttachmentObject stored = store.put("quarantine/1/7/file.txt", content,
                "text/plain", Map.of("tenant-id", "1"));

        assertEquals(64, stored.etag().length());
        assertTrue(store.exists("quarantine/1/7/file.txt"));
        try (MaterializedAttachment materialized = store.materialize("quarantine/1/7/file.txt")) {
            assertEquals("safe evidence", Files.readString(materialized.path()));
        }

        store.move("quarantine/1/7/file.txt", "clean/1/7/file.txt");
        assertFalse(store.exists("quarantine/1/7/file.txt"));
        assertTrue(store.exists("clean/1/7/file.txt"));
        assertEquals("safe evidence", store.load("clean/1/7/file.txt").getContentAsString(StandardCharsets.UTF_8));

        store.delete("clean/1/7/file.txt");
        assertFalse(store.exists("clean/1/7/file.txt"));
    }

    @Test
    void rejectsObjectKeyTraversal() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.setUploadDir(tempDir.toString());
        LocalAttachmentObjectStore store = new LocalAttachmentObjectStore(properties);

        assertThrows(AttachmentStorageException.class,
                () -> store.put("../escaped.txt", new byte[]{1}, "text/plain", Map.of()));
    }
}
