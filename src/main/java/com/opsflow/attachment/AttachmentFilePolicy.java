package com.opsflow.attachment;

import com.opsflow.common.BusinessException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AttachmentFilePolicy {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "pdf", "txt", "md", "csv",
            "doc", "docx", "xls", "xlsx", "zip", "log");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "log");
    private static final Map<String, String> CANONICAL_CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"), Map.entry("csv", "text/csv"),
            Map.entry("log", "text/plain"), Map.entry("zip", "application/zip"),
            Map.entry("doc", "application/msword"), Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    private AttachmentFilePolicy() { }

    static String safeOriginalName(String value) {
        if (!StringUtils.hasText(value)) throw new BusinessException("附件文件名不能为空");
        String normalized;
        try {
            normalized = StringUtils.cleanPath(value).replace("\\", "/");
        } catch (InvalidPathException ex) {
            throw new BusinessException("附件文件名非法");
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (!StringUtils.hasText(name) || name.equals(".") || name.equals("..") || name.contains("\u0000")) {
            throw new BusinessException("附件文件名非法");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String canonicalContentType(String originalName) {
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("不支持的附件类型，请上传图片、文档、表格、文本或压缩包");
        }
        return CANONICAL_CONTENT_TYPES.get(extension);
    }

    static String verifyBytes(byte[] content, String originalName) {
        String extension = extensionOf(originalName);
        canonicalContentType(originalName);
        boolean valid = switch (extension) {
            case "png" -> startsWith(content, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "jpg", "jpeg" -> startsWith(content, 0xff, 0xd8, 0xff);
            case "gif" -> startsWithAscii(content, "GIF87a") || startsWithAscii(content, "GIF89a");
            case "pdf" -> startsWithAscii(content, "%PDF-");
            case "zip", "docx", "xlsx" -> isZip(content);
            case "doc", "xls" -> startsWith(content, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1);
            case "txt", "md", "csv", "log" -> isUtf8Text(content);
            default -> false;
        };
        if (!valid) throw new BusinessException("附件实际内容与文件扩展名不匹配");
        return CANONICAL_CONTENT_TYPES.get(extension);
    }

    static boolean pathMatchesDeclaredType(Path path, String originalName) {
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) return false;
        if (TEXT_EXTENSIONS.contains(extension)) return isUtf8Text(path);
        byte[] prefix = new byte[8];
        int read;
        try (var input = Files.newInputStream(path)) {
            read = input.read(prefix);
        } catch (IOException ex) {
            throw new AttachmentThreatScanException("附件类型校验失败", ex, true);
        }
        if (read < 0) read = 0;
        byte[] actual = read == prefix.length ? prefix : java.util.Arrays.copyOf(prefix, read);
        return switch (extension) {
            case "png" -> startsWith(actual, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "jpg", "jpeg" -> startsWith(actual, 0xff, 0xd8, 0xff);
            case "gif" -> startsWithAscii(actual, "GIF87a") || startsWithAscii(actual, "GIF89a");
            case "pdf" -> startsWithAscii(actual, "%PDF-");
            case "zip", "docx", "xlsx" -> isZip(actual);
            case "doc", "xls" -> startsWith(actual, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1);
            default -> false;
        };
    }

    private static boolean isUtf8Text(byte[] content) {
        for (byte item : content) if (item == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private static boolean isUtf8Text(Path path) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new java.io.InputStreamReader(Files.newInputStream(path), decoder)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) if (buffer[index] == 0) return false;
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isZip(byte[] content) {
        return startsWith(content, 0x50, 0x4b, 0x03, 0x04)
                || startsWith(content, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(content, 0x50, 0x4b, 0x07, 0x08);
    }

    private static boolean startsWithAscii(byte[] content, String signature) {
        return startsWith(content, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (content[i] != signature[i]) return false;
        return true;
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }
}
