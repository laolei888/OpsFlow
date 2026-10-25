package com.opsflow.attachment;

public record StoredObjectMetadata(long sizeBytes, String etag, String versionId) { }
