package com.opsflow.search;

import java.time.LocalDateTime;
import java.util.List;

public record GlobalSearchView(String query, long total, List<Group> groups, List<Result> results) {
    public record Group(String type, String label, long count) { }

    public record Result(Long id, String type, String typeLabel, String referenceNo,
                         String title, String snippet, String status, String targetView,
                         LocalDateTime updatedAt, int score) { }
}
