package com.opsflow.attachment;

import java.time.Instant;

public record StoredObjectInventoryItem(String key, long sizeBytes, Instant lastModified,
                                        String etag, String versionId,
                                        boolean latest, boolean deleteMarker) { }
