package com.opsflow.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@Component
public class AttachmentThreatScanner {
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    private static final Set<String> BLOCKED_ARCHIVE_EXTENSIONS = Set.of(
            "exe", "dll", "com", "scr", "msi", "bat", "cmd", "ps1", "vbs", "vbe",
            "js", "jse", "wsf", "hta", "jar", "class", "sh");

    @Value("${opsflow.attachment-security.clamav.enabled:false}")
    private boolean clamAvEnabled;
    @Value("${opsflow.attachment-security.clamav.host:localhost}")
    private String clamAvHost;
    @Value("${opsflow.attachment-security.clamav.port:3310}")
    private int clamAvPort;
    @Value("${opsflow.attachment-security.clamav.timeout-ms:5000}")
    private int clamAvTimeoutMs;
    @Value("${opsflow.attachment-security.archive.max-entries:2000}")
    private int maxArchiveEntries;
    @Value("${opsflow.attachment-security.archive.max-expanded-bytes:104857600}")
    private long maxExpandedBytes;
    @Value("${opsflow.attachment-security.archive.max-ratio:100}")
    private long maxCompressionRatio;

    public ScanResult scan(Path path, TicketAttachment attachment) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new AttachmentThreatScanException("隔离文件不存在或不可读", true);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new AttachmentThreatScanException("隔离文件读取失败", ex, true);
        }
        String sha256 = sha256(content);
        try {
            String binaryText = new String(content, StandardCharsets.ISO_8859_1);
            if (binaryText.contains(EICAR)) {
                return ScanResult.infected("LOCAL_POLICY", "EICAR_TEST_SIGNATURE", sha256);
            }
            if (startsWith(content, 0x4d, 0x5a) || startsWith(content, 0x7f, 0x45, 0x4c, 0x46)) {
                return ScanResult.infected("LOCAL_POLICY", "EXECUTABLE_BINARY_SIGNATURE", sha256);
            }
            String extension = extensionOf(attachment == null ? null : attachment.getOriginalName());
            if (Set.of("zip", "docx", "xlsx").contains(extension)) {
                String archiveThreat = inspectArchive(path, extension);
                if (archiveThreat != null) return ScanResult.infected("LOCAL_POLICY", archiveThreat, sha256);
            }
            if (!clamAvEnabled) return ScanResult.clean("LOCAL_POLICY", "NO_THREAT_FOUND", sha256);

            String response = scanWithClamAv(path);
            if (response.contains(" FOUND")) {
                String signature = response.substring(0, response.indexOf(" FOUND"));
                int separator = signature.indexOf(':');
                if (separator >= 0) signature = signature.substring(separator + 1).trim();
                return ScanResult.infected("LOCAL_POLICY+CLAMAV", safeResult(signature), sha256);
            }
            if (!response.contains(" OK")) {
                throw new AttachmentThreatScanException("ClamAV 返回异常结果：" + safeResult(response), true);
            }
            return ScanResult.clean("LOCAL_POLICY+CLAMAV", "NO_THREAT_FOUND", sha256);
        } catch (AttachmentThreatScanException ex) {
            throw new AttachmentThreatScanException(ex.getMessage(), ex, ex.retryable(), sha256);
        }
    }

    private String inspectArchive(Path path, String outerExtension) {
        int entries = 0;
        long expanded = 0;
        long compressedSize;
        boolean officeContentTypes = false;
        boolean officeMainDocument = false;
        byte[] buffer = new byte[8192];
        byte[] eicarBytes = EICAR.getBytes(StandardCharsets.ISO_8859_1);
        try {
            compressedSize = Math.max(1L, Files.size(path));
        } catch (IOException ex) {
            throw new AttachmentThreatScanException("压缩包安全检查失败", ex, true);
        }
        try (ZipFile archive = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> archiveEntries = archive.entries();
            while (archiveEntries.hasMoreElements()) {
                ZipEntry entry = archiveEntries.nextElement();
                if (++entries > maxArchiveEntries) return "ARCHIVE_ENTRY_LIMIT_EXCEEDED";
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    return "ARCHIVE_PATH_TRAVERSAL";
                }
                String normalized = name.toLowerCase(Locale.ROOT);
                if (normalized.equals("[content_types].xml")) officeContentTypes = true;
                if (outerExtension.equals("docx") && normalized.equals("word/document.xml")) {
                    officeMainDocument = true;
                }
                if (outerExtension.equals("xlsx") && normalized.equals("xl/workbook.xml")) {
                    officeMainDocument = true;
                }
                if ((outerExtension.equals("docx") || outerExtension.equals("xlsx"))
                        && normalized.endsWith("vbaproject.bin")) {
                    return "UNEXPECTED_OFFICE_MACRO";
                }
                if (outerExtension.equals("zip") && BLOCKED_ARCHIVE_EXTENSIONS.contains(extensionOf(name))) {
                    return "ARCHIVE_CONTAINS_EXECUTABLE";
                }
                if (entry.isDirectory()) continue;
                int signatureLength = 0;
                int eicarMatch = 0;
                byte[] signature = new byte[4];
                try (InputStream input = archive.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        expanded += read;
                        if (expanded > maxExpandedBytes) return "ARCHIVE_EXPANSION_LIMIT_EXCEEDED";
                        if (expanded > compressedSize * maxCompressionRatio) {
                            return "ARCHIVE_COMPRESSION_RATIO_EXCEEDED";
                        }
                        for (int i = 0; i < read; i++) {
                            byte current = buffer[i];
                            if (signatureLength < signature.length) signature[signatureLength++] = current;
                            if (current == eicarBytes[eicarMatch]) {
                                eicarMatch++;
                                if (eicarMatch == eicarBytes.length) return "ARCHIVE_EICAR_TEST_SIGNATURE";
                            } else {
                                eicarMatch = current == eicarBytes[0] ? 1 : 0;
                            }
                        }
                    }
                }
                if (startsWith(signature, 0x4d, 0x5a) || startsWith(signature, 0x7f, 0x45, 0x4c, 0x46)) {
                    return "ARCHIVE_CONTAINS_EXECUTABLE_SIGNATURE";
                }
            }
            if ((outerExtension.equals("docx") || outerExtension.equals("xlsx"))
                    && (!officeContentTypes || !officeMainDocument)) return "OFFICE_STRUCTURE_INVALID";
        } catch (ZipException ex) {
            return "ARCHIVE_INVALID_STRUCTURE";
        } catch (IOException ex) {
            throw new AttachmentThreatScanException("压缩包安全检查失败", ex, true);
        }
        return null;
    }

    private String scanWithClamAv(Path path) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamAvHost, clamAvPort), clamAvTimeoutMs);
            socket.setSoTimeout(clamAvTimeoutMs);
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                 InputStream file = Files.newInputStream(path)) {
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[8192];
                int read;
                while ((read = file.read(buffer)) >= 0) {
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                }
                output.writeInt(0);
                output.flush();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                int current;
                while ((current = socket.getInputStream().read()) >= 0 && current != 0 && response.size() < 4096) {
                    response.write(current);
                }
                String value = response.toString(StandardCharsets.UTF_8).trim();
                if (value.isBlank()) throw new IOException("empty response");
                return value;
            }
        } catch (IOException ex) {
            throw new AttachmentThreatScanException("ClamAV 服务不可用", ex, true);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    private String safeResult(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }

    public record ScanResult(boolean clean, String engine, String result, String sha256) {
        public static ScanResult clean(String engine, String result, String sha256) {
            return new ScanResult(true, engine, result, sha256);
        }

        public static ScanResult infected(String engine, String result, String sha256) {
            return new ScanResult(false, engine, result, sha256);
        }
    }
}
