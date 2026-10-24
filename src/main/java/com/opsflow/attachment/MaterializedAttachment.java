package com.opsflow.attachment;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MaterializedAttachment implements AutoCloseable {
    private final Path path;
    private final Runnable cleanup;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MaterializedAttachment(Path path, Runnable cleanup) {
        this.path = path;
        this.cleanup = cleanup;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) cleanup.run();
    }
}
