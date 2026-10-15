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
    void appliesEveryMigrationThroughV38() {
        assertNotNull(flyway.info().current());
        assertEquals("38", flyway.info().current().getVersion().getVersion());
        assertEquals(38, flyway.info().applied().length);
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

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
