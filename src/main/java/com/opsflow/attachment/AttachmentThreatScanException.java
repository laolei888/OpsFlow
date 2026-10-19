package com.opsflow.attachment;

public class AttachmentThreatScanException extends RuntimeException {
    private final boolean retryable;
    private final String sha256;

    public AttachmentThreatScanException(String message, boolean retryable) {
        this(message, null, retryable, null);
    }

    public AttachmentThreatScanException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, null);
    }

    public AttachmentThreatScanException(String message, Throwable cause, boolean retryable, String sha256) {
        super(message, cause);
        this.retryable = retryable;
        this.sha256 = sha256;
    }

    public boolean retryable() {
        return retryable;
    }

    public String sha256() {
        return sha256;
    }
}
