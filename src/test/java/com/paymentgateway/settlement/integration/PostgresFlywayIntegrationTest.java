package com.paymentgateway.settlement.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration test: verifies PostgreSQL connectivity + Flyway
 * migrations execute on container startup.
 *
 * <p>Requires Docker (Testcontainers).</p>
 */
@Testcontainers
@SpringBootTest
class PostgresFlywayIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayMigrationsApplyAndDatabaseIsAccessible() throws SQLException {
        assertThatCode(() -> POSTGRES.getJdbcUrl()).doesNotThrowAnyException();
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.isValid(1)).isTrue();
        }
        // Flyway should have created app_metadata and ledger_account_group
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables " +
                             "WHERE table_name = 'app_metadata'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
        }
    }
}
