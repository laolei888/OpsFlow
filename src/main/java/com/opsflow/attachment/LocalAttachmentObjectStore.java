package com.opsflow.attachment;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LocalAttachmentObjectStore implements AttachmentObjectStore {
    private final Path root;

    public LocalAttachmentObjectStore(AttachmentStorageProperties properties) {
        this.root = Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
    }

    @Override
    public AttachmentStorageType type() {
        return AttachmentStorageType.LOCAL;
    }

    @Override
    public StoredAttachmentObject put(String key, byte[] content, String contentType, Map<String, String> metadata) {
        Path target = resolve(key);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, content);
            moveFile(temporary, target);
            return new StoredAttachmentObject(sha256(content), null);
        } catch (IOException ex) {
            deleteQuietly(temporary);
            throw new AttachmentStorageException("本地附件写入失败", ex);
        }
    }

    @Override
    public Resource load(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new AttachmentStorageException("附件对象不存在或不可读");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException ex) {
            throw new AttachmentStorageException("附件对象地址无效", ex);
        }
    }

    @Override
    public MaterializedAttachment materialize(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new AttachmentStorageException("附件对象不存在或不可读");
        }
        return new MaterializedAttachment(target, () -> { });
    }

    @Override
    public StoredAttachmentObject move(String sourceKey, String targetKey) {
        Path source = resolve(sourceKey);
        Path target = resolve(targetKey);
        if (!Files.isRegularFile(source)) throw new AttachmentStorageException("附件源对象不存在");
        if (Files.exists(target)) throw new AttachmentStorageException("附件目标对象已存在");
        try {
            Files.createDirectories(target.getParent());
            moveFile(source, target);
            return new StoredAttachmentObject(sha256(target), null);
        } catch (IOException ex) {
            throw new AttachmentStorageException("本地附件移动失败", ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new AttachmentStorageException("本地附件删除失败", ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public boolean supportsInventory() {
        return true;
    }

    @Override
    public StoredObjectInventory inventory(String prefix, int limit) {
        Path base = resolve(prefix);
        if (!Files.exists(base)) return new StoredObjectInventory(List.of(), false);
        int safeLimit = Math.max(1, limit);
        List<StoredObjectInventoryItem> items = new ArrayList<>();
        try (var paths = Files.walk(base)) {
            var iterator = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).iterator();
            while (iterator.hasNext() && items.size() <= safeLimit) {
                Path path = iterator.next();
                String key = root.relativize(path).toString().replace('\\', '/');
                Instant modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
                items.add(new StoredObjectInventoryItem(key, Files.size(path), modified,
                        null, null, true, false));
            }
        } catch (IOException ex) {
            throw new AttachmentStorageException("本地附件对象巡检失败", ex);
        }
        boolean truncated = items.size() > safeLimit;
        if (truncated) items = new ArrayList<>(items.subList(0, safeLimit));
        return new StoredObjectInventory(items, truncated);
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new AttachmentStorageException("附件对象键非法");
        return target;
    }

    private void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException atomicFailure) {
            try {
                Files.move(source, target);
                return;
            } catch (IOException moveFailure) {
                try {
                    Files.copy(source, target);
                    Files.delete(source);
                } catch (IOException copyFailure) {
                    deleteQuietly(target);
                    copyFailure.addSuppressed(atomicFailure);
                    copyFailure.addSuppressed(moveFailure);
                    throw copyFailure;
                }
            }
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the original storage failure.
        }
    }
}
