package com.opsflow.capacity;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CapacitySnapshotCollector {
    private final JdbcTemplate jdbcTemplate;

    public CapacityMeasurement collect(Long tenantId) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        Runtime runtime = Runtime.getRuntime();
        return new CapacityMeasurement(
                count("SELECT COUNT(*) FROM users WHERE tenant_id = ? AND status = 'ACTIVE'", tenantId),
                count("SELECT COUNT(*) FROM tickets WHERE tenant_id = ?", tenantId),
                count("SELECT COUNT(*) FROM tickets WHERE tenant_id = ? AND status IN ('OPEN','IN_PROGRESS','WAITING_CONFIRMATION')", tenantId),
                count("SELECT COUNT(*) FROM tickets WHERE tenant_id = ? AND status IN ('OPEN','IN_PROGRESS','WAITING_CONFIRMATION') AND due_at IS NOT NULL AND due_at < NOW()", tenantId),
                count("SELECT COUNT(*) FROM tickets WHERE tenant_id = ? AND created_at >= ?", tenantId, since),
                count("SELECT COUNT(*) FROM tickets WHERE tenant_id = ? AND status IN ('RESOLVED','CLOSED') AND updated_at >= ?", tenantId, since),
                count("SELECT COUNT(*) FROM knowledge_articles WHERE tenant_id = ? AND status = 'PUBLISHED'", tenantId),
                count("SELECT COUNT(*) FROM cmdb_assets WHERE tenant_id = ? AND status <> 'RETIRED'", tenantId),
                count("SELECT COUNT(*) FROM incidents WHERE tenant_id = ? AND status NOT IN ('RESOLVED','CLOSED','CANCELLED')", tenantId),
                count("SELECT COUNT(*) FROM monitoring_alerts WHERE tenant_id = ? AND status IN ('FIRING','ACKNOWLEDGED')", tenantId),
                count("SELECT COUNT(*) FROM async_message_outbox WHERE tenant_id = ? AND status IN ('PENDING','PROCESSING','FAILED')", tenantId),
                count("SELECT COUNT(*) FROM ticket_attachments WHERE tenant_id = ? AND scan_status IN ('PENDING','SCANNING')", tenantId),
                count("SELECT COALESCE(SUM(size_bytes), 0) FROM ticket_attachments WHERE tenant_id = ?", tenantId),
                count("SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = DATABASE()"),
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory()
        );
    }

    public List<Long> activeTenantIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM users WHERE status = 'ACTIVE' ORDER BY tenant_id",
                Long.class);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    public record CapacityMeasurement(long activeUsers, long totalTickets, long activeTickets,
                                      long overdueTickets, long ticketsCreated24h, long ticketsResolved24h,
                                      long publishedArticles, long activeAssets, long activeIncidents,
                                      long firingAlerts, long asyncBacklog, long attachmentBacklog,
                                      long attachmentBytes, long databaseBytes, long heapUsedBytes,
                                      long heapMaxBytes) {
    }
}
