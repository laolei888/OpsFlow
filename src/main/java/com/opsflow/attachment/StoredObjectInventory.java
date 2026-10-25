package com.opsflow.attachment;

import java.util.List;

public record StoredObjectInventory(List<StoredObjectInventoryItem> items, boolean truncated) {
    public StoredObjectInventory {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
