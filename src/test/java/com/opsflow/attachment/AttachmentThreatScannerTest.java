package com.opsflow.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentThreatScannerTest {
    private final AttachmentThreatScanner scanner = new AttachmentThreatScanner();

    @TempDir
    Path tempDir;

    @BeforeEach
    void configureScanner() {
        ReflectionTestUtils.setField(scanner, "clamAvEnabled", false);
        ReflectionTestUtils.setField(scanner, "maxArchiveEntries", 100);
        ReflectionTestUtils.setField(scanner, "maxExpandedBytes", 10L * 1024 * 1024);
        ReflectionTestUtils.setField(scanner, "maxCompressionRatio", 1000L);
    }

    @Test
    void acceptsCleanTextAndProducesSha256Evidence() throws IOException {
        Path file = Files.writeString(tempDir.resolve("diagnostic.txt"), "safe diagnostic output");

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("diagnostic.txt"));

        assertTrue(result.clean());
        assertEquals("LOCAL_POLICY", result.engine());
        assertEquals(64, result.sha256().length());
    }

    @Test
    void detectsEicarTestSignature() throws IOException {
        String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        Path file = Files.writeString(tempDir.resolve("eicar.txt"), eicar, StandardCharsets.ISO_8859_1);

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("eicar.txt"));

        assertFalse(result.clean());
        assertEquals("EICAR_TEST_SIGNATURE", result.result());
    }

    @Test
    void blocksExecutableHiddenInsideZipArchive() throws IOException {
        Path file = tempDir.resolve("evidence.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry("tools/repair.exe"));
            output.write(new byte[]{0x4d, 0x5a, 0x00, 0x00});
            output.closeEntry();
        }

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("evidence.zip"));

        assertFalse(result.clean());
        assertEquals("ARCHIVE_CONTAINS_EXECUTABLE", result.result());
    }

    @Test
    void blocksEicarSignatureHiddenInsideZipArchive() throws IOException {
        Path file = tempDir.resolve("eicar-evidence.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry("evidence/readme.txt"));
            output.write("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                    .getBytes(StandardCharsets.ISO_8859_1));
            output.closeEntry();
        }

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("eicar-evidence.zip"));

        assertFalse(result.clean());
        assertEquals("ARCHIVE_EICAR_TEST_SIGNATURE", result.result());
    }

    @Test
    void blocksArchivePathTraversal() throws IOException {
        Path file = tempDir.resolve("traversal.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry("../outside.txt"));
            output.write("unsafe".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("traversal.zip"));

        assertFalse(result.clean());
        assertEquals("ARCHIVE_PATH_TRAVERSAL", result.result());
    }

    @Test
    void quarantinesMalformedArchiveAndPreservesHashEvidence() throws IOException {
        Path file = Files.write(tempDir.resolve("broken.zip"), new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00});

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("broken.zip"));

        assertFalse(result.clean());
        assertEquals("ARCHIVE_INVALID_STRUCTURE", result.result());
        assertEquals(64, result.sha256().length());
    }

    @Test
    void streamsLargeTextWithoutLoadingWholeFileAndPreservesHash() throws IOException {
        Path file = tempDir.resolve("large.log");
        byte[] block = "streamed diagnostic line\n".repeat(4096).getBytes(StandardCharsets.UTF_8);
        try (var output = Files.newOutputStream(file)) {
            for (int index = 0; index < 160; index++) output.write(block);
        }

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("large.log"));

        assertTrue(Files.size(file) > 10L * 1024 * 1024);
        assertTrue(result.clean());
        assertEquals(64, result.sha256().length());
    }

    @Test
    void blocksContentWhoseSignatureDoesNotMatchDeclaredExtension() throws IOException {
        Path file = Files.writeString(tempDir.resolve("fake.pdf"), "this is not a PDF");

        AttachmentThreatScanner.ScanResult result = scanner.scan(file, attachment("fake.pdf"));

        assertFalse(result.clean());
        assertEquals("CONTENT_TYPE_MISMATCH", result.result());
    }

    private TicketAttachment attachment(String name) {
        TicketAttachment attachment = new TicketAttachment();
        attachment.setOriginalName(name);
        return attachment;
    }
}
