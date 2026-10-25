package com.opsflow.attachment;

public record MultipartUploadedPart(int partNumber, String etag, long sizeBytes) { }
