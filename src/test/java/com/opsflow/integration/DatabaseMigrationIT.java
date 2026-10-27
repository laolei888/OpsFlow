package com.opsflow.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseMigrationIT {
    private static MySQLContainer<?> mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private static Flyway flyway;

    @BeforeAll
    static void migrateSchema() {
        configureDatabase();
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @BeforeEach
    void cleanIntegrationFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM ticket_satisfaction_responses");
            statement.executeUpdate("DELETE FROM notification_deliveries");
            statement.executeUpdate("DELETE FROM notification_template_versions");
            statement.executeUpdate("DELETE FROM notification_templates");
            statement.executeUpdate("DELETE FROM notification_event_preferences");
            statement.executeUpdate("DELETE FROM notification_channel_profiles");
            statement.executeUpdate("DELETE FROM oidc_login_exchanges");
            statement.executeUpdate("DELETE FROM user_permission_overrides");
            statement.executeUpdate("DELETE FROM user_external_identities");
            statement.executeUpdate("DELETE FROM oidc_provider_mappings");
            statement.executeUpdate("DELETE FROM chaos_experiment_checks");
            statement.executeUpdate("DELETE FROM chaos_experiments");
            statement.executeUpdate("DELETE FROM recovery_drill_checks");
            statement.executeUpdate("DELETE FROM recovery_drills");
            statement.executeUpdate("DELETE FROM backup_agent_callbacks");
            statement.executeUpdate("DELETE FROM backup_agents");
            statement.executeUpdate("DELETE FROM backup_snapshots");
            statement.executeUpdate("DELETE FROM disaster_recovery_policies");
            statement.executeUpdate("DELETE FROM attachment_storage_findings");
            statement.executeUpdate("DELETE FROM attachment_storage_policies");
            statement.executeUpdate("DELETE FROM attachment_upload_sessions");
            statement.executeUpdate("DELETE FROM async_dead_letter_messages");
            statement.executeUpdate("DELETE FROM async_message_outbox");
        }
    }

    private static void configureDatabase() {
        String externalJdbcUrl = System.getenv("OPSFLOW_IT_JDBC_URL");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            jdbcUrl = externalJdbcUrl;
            username = environmentOrDefault("OPSFLOW_IT_DB_USERNAME", "opsflow");
            password = environmentOrDefault("OPSFLOW_IT_DB_PASSWORD", "opsflow");
            return;
        }

        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("opsflow_integration")
                .withUsername("opsflow")
                .withPassword("opsflow");
        mysql.start();
        jdbcUrl = mysql.getJdbcUrl();
        username = mysql.getUsername();
        password = mysql.getPassword();
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @AfterAll
    static void stopContainer() {
        if (mysql != null && mysql.isRunning()) {
            mysql.stop();
        }
    }

    @Test
    void appliesEveryMigrationThroughV57() throws SQLException {
        assertNotNull(flyway.info().current());
        assertEquals("57", flyway.info().current().getVersion().getVersion());
        assertEquals(57, flyway.info().applied().length);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'knowledge_articles'
                       AND column_name IN ('review_requested_by', 'review_requested_at',
                                           'reviewed_by', 'reviewed_at', 'review_comment',
                                           'sensitive_data_checked', 'scope_defined',
                                           'verification_steps_complete', 'review_due_at')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(9, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'attachment_upload_sessions'
                       AND column_name IN ('session_key', 'client_upload_id', 'provider_upload_id',
                                           'status', 'part_size', 'total_parts', 'expires_at')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(7, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('disaster_recovery_policies', 'backup_snapshots',
                                          'recovery_drills', 'recovery_drill_checks',
                                          'backup_agents', 'backup_agent_callbacks',
                                          'chaos_experiments', 'chaos_experiment_checks')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(8, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'recovery_drills'
                       AND column_name IN ('version', 'target_rpo_minutes', 'target_rto_minutes',
                                           'achieved_rpo_minutes', 'achieved_rto_minutes',
                                           'integrity_score', 'evidence_note')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(7, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('attachment_storage_policies', 'attachment_storage_findings')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND (
                         (table_name = 'notification_templates' AND column_name = 'locale') OR
                         (table_name = 'notification_template_versions' AND column_name IN
                            ('submitted_by', 'submitted_at', 'reviewed_by', 'reviewed_at', 'review_comment')) OR
                         (table_name = 'notification_channel_profiles' AND column_name IN
                            ('preferred_locale', 'time_zone', 'delivery_window_enabled',
                             'delivery_window_start', 'delivery_window_end', 'urgent_bypass')) OR
                         (table_name = 'notification_deliveries' AND column_name IN
                            ('delivery_locale', 'scheduled_reason'))
                     )
                     """)) {
            ResultSet result = statement.executeQuery();
            result.next();
            assertEquals(14, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'attachment_storage_findings'
                       AND column_name IN ('finding_fingerprint', 'finding_type', 'object_key',
                                           'version_id', 'eligible_delete_at', 'last_seen_at')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(6, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('oidc_provider_mappings', 'user_external_identities',
                                          'oidc_login_exchanges', 'permissions', 'role_permissions',
                                          'user_permission_overrides')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(6, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'users'
                       AND column_name IN ('email', 'auth_source', 'last_login_at')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(3, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'notifications'
                       AND column_name = 'in_app_enabled'
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'ticket_attachments'
                       AND column_name IN ('scan_status', 'sha256', 'next_scan_at', 'quarantined_at', 'released_at',
                                           'storage_backend', 'storage_etag', 'storage_version')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(8, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('notification_templates', 'notification_template_versions')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries'
                       AND column_name IN ('template_version_id', 'rendered_title', 'rendered_content')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(3, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('capacity_policies', 'capacity_maintenance_windows')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'capacity_alerts'
                       AND column_name IN ('policy_id', 'notification_suppressed',
                                           'suppressed_notification_count', 'suppressed_by_window_id',
                                           'suppressed_reason', 'last_suppressed_at')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(6, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'capacity_alerts'
                       AND column_name IN ('metric_key', 'level', 'status', 'active_key', 'projected_days')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(5, result.getInt(1));
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'capacity_snapshots'
                       AND column_name IN ('capture_type', 'app_version', 'release_id', 'database_bytes', 'heap_used_bytes')
                     """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertEquals(5, result.getInt(1));
        }
    }

    @Test
    void enforcesOneSatisfactionResponsePerTenantTicket() throws SQLException {
        insertResponse(1L, 1001L, 7L, 9L, 5L, 5);

        assertThrows(SQLException.class,
                () -> insertResponse(1L, 1001L, 7L, 9L, 5L, 4));

        insertResponse(2L, 1001L, 17L, 19L, 15L, 4);
    }

    @Test
    void aggregatesCsatInMysqlWithoutLoadingRawHistory() throws SQLException {
        insertResponse(1L, 1001L, 7L, 9L, 5L, 5);
        insertResponse(1L, 1002L, 8L, 9L, 5L, 4);
        insertResponse(1L, 1003L, 10L, 11L, 6L, 1);

        String sql = """
                SELECT COUNT(*) AS total_responses,
                       ROUND(AVG(rating), 2) AS average_rating,
                       SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END) AS positive_count,
                       SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END) AS low_rating_count
                FROM ticket_satisfaction_responses
                WHERE tenant_id = 1
                """;
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            assertEquals(3, result.getLong("total_responses"));
            assertEquals(new BigDecimal("3.33"), result.getBigDecimal("average_rating"));
            assertEquals(2, result.getLong("positive_count"));
            assertEquals(1, result.getLong("low_rating_count"));
        }
    }

    @Test
    void enforcesAsyncNotificationDedupePerTenantAndRecipient() throws SQLException {
        insertAsyncMessage(1L, 7L, "message-0000000000000000000000000001", "REPORT:9");

        assertThrows(SQLException.class,
                () -> insertAsyncMessage(1L, 7L, "message-0000000000000000000000000002", "REPORT:9"));

        insertAsyncMessage(1L, 8L, "message-0000000000000000000000000003", "REPORT:9");
    }

    @Test
    void enforcesOneDeadLetterRecordPerTenantAndMessage() throws SQLException {
        insertDeadLetter(1L, "message-0000000000000000000000000001");
        assertThrows(SQLException.class,
                () -> insertDeadLetter(1L, "message-0000000000000000000000000001"));
        insertDeadLetter(2L, "message-0000000000000000000000000001");
    }

    @Test
    void enforcesOneNotificationPreferencePerTenantUserAndEvent() throws SQLException {
        insertNotificationPreference(1L, 7L, "SLA_OVERDUE");
        assertThrows(SQLException.class,
                () -> insertNotificationPreference(1L, 7L, "SLA_OVERDUE"));
        insertNotificationPreference(1L, 7L, "INCIDENT_DECLARED");
        insertNotificationPreference(2L, 7L, "SLA_OVERDUE");
    }

    private void insertResponse(Long tenantId, Long ticketId, Long requesterId,
                                Long assigneeId, Long groupId, int rating) throws SQLException {
        String sql = """
                INSERT INTO ticket_satisfaction_responses
                    (tenant_id, ticket_id, requester_id, assignee_id, assignment_group_id,
                     rating, tags, comment, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PROFESSIONAL', 'integration test', NOW())
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, ticketId);
            statement.setLong(3, requesterId);
            statement.setLong(4, assigneeId);
            statement.setLong(5, groupId);
            statement.setInt(6, rating);
            statement.executeUpdate();
        }
    }

    private void insertAsyncMessage(Long tenantId, Long recipientId,
                                    String messageId, String dedupeKey) throws SQLException {
        String sql = """
                INSERT INTO async_message_outbox
                    (tenant_id, recipient_id, message_id, event_type, routing_key,
                     aggregate_type, aggregate_id, dedupe_key, payload_json, status,
                     attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'NOTIFICATION_REQUESTED', 'notification.requested.v1',
                        'REPORT_SUBSCRIPTION', 9, ?, '{}', 'PENDING', 0, NOW(), NOW(), NOW())
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, recipientId);
            statement.setString(3, messageId);
            statement.setString(4, dedupeKey);
            statement.executeUpdate();
        }
    }

    private void insertDeadLetter(Long tenantId, String messageId) throws SQLException {
        String sql = """
                INSERT INTO async_dead_letter_messages
                    (tenant_id, message_id, source_queue, original_routing_key, dead_letter_reason,
                     payload_json, status, quarantined_at, updated_at)
                VALUES (?, ?, 'opsflow.notifications.v1', 'notification.requested.v1', 'rejected',
                        '{}', 'QUARANTINED', NOW(), NOW())
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            statement.setString(2, messageId);
            statement.executeUpdate();
        }
    }

    private void insertNotificationPreference(Long tenantId, Long userId, String eventType) throws SQLException {
        String sql = """
                INSERT INTO notification_event_preferences
                    (tenant_id, user_id, event_type, in_app_enabled, email_enabled, webhook_enabled,
                     created_at, updated_at)
                VALUES (?, ?, ?, TRUE, FALSE, FALSE, NOW(), NOW())
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, userId);
            statement.setString(3, eventType);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
