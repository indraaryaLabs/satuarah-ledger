package click.satuarah.ledger.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One real PostgreSQL for the whole test run. Starting a database costs seconds, so every test
 * class shares it and keeps out of the others' way with unique account codes instead.
 */
public final class PostgresSupport {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:17-alpine")
            .withEnv("POSTGRES_DB", "ledger")
            .withEnv("POSTGRES_USER", "ledger")
            .withEnv("POSTGRES_PASSWORD", "ledger")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private static volatile boolean started;

    private PostgresSupport() {}

    public static synchronized String url() {
        if (!started) {
            POSTGRES.start();
            awaitConnections();
            started = true;
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/ledger";
    }

    public static String user() {
        return "ledger";
    }

    public static String password() {
        return "ledger";
    }

    /** A data source on the shared database with the schema applied (once) by Flyway. */
    public static synchronized DataSource migratedDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url(), user(), password());
        Flyway.configure().dataSource(dataSource).load().migrate();
        return dataSource;
    }

    private static void awaitConnections() {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/ledger";
        SQLException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(url, user(), password())) {
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the database", interrupted);
                }
            }
        }
        throw new IllegalStateException("the test database never accepted a connection", last);
    }
}
