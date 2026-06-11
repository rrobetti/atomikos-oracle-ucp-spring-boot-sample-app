package com.example.atomikos;

import com.atomikos.jdbc.AtomikosNonPoolingDataSourceBean;
import com.example.atomikos.service.AccountService;
import oracle.ucp.jdbc.PoolXADataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.OracleContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests proving the Atomikos + Oracle UCP wiring against a real
 * Oracle database running in a Testcontainer.
 *
 * <p>A real Oracle instance is required to exercise Oracle UCP's
 * {@code PoolXADataSource} and Oracle's XA driver
 * ({@code oracle.jdbc.xa.client.OracleXADataSource}).  H2 cannot be used here
 * because Oracle UCP's XA connection factory only works with drivers that
 * expose a genuine {@code javax.sql.XADataSource} compatible with Oracle's
 * internal connection pool logic.
 *
 * <p>{@code src/test/resources/jta.properties} sets
 * {@code com.atomikos.icatch.enable_logging=false}, which disables Atomikos's
 * XA recovery scan and removes the need for Oracle XA recovery privileges
 * inside the test container.
 */
@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountServiceIntegrationTest {

    /**
     * Oracle XE 21c slim fast-start image — validated to work with the
     * Testcontainers oracle-xe module.
     *
     * <p>The container is shared across all tests in this class to avoid the
     * significant startup cost of Oracle (~30–60 s) on every test method.
     */
    @Container
    static final OracleContainer oracle =
            new OracleContainer("gvenzl/oracle-xe:21.3.0-slim")
                    .withDatabaseName("testdb")
                    .withUsername("testuser")
                    .withPassword("testpwd");

    /**
     * Injects the container's JDBC URL, username and password into the Spring
     * {@code Environment} <em>before</em> the application context is created.
     *
     * <p>{@link DataSourceConfig} reads these three properties via
     * {@code @Value("${datasource.url}")} etc., so the UCP
     * {@code PoolXADataSource} points at the Testcontainer Oracle instance.
     */
    @DynamicPropertySource
    static void registerOracleProperties(DynamicPropertyRegistry registry) {
        if (!oracle.isRunning()) {
            oracle.start();
        }

        String jdbcUrl = oracle.getJdbcUrl();
        String username = oracle.getUsername();
        String password = oracle.getPassword();

        registry.add("datasource.url", () -> jdbcUrl);
        registry.add("datasource.username", () -> username);
        registry.add("datasource.password", () -> password);
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    // ------------------------------------------------------------------
    // Schema setup (once per test class – shared container)
    // ------------------------------------------------------------------

    @BeforeAll
    void createSchema() throws Exception {
        try (Connection connection = openContainerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (" +
                    "  id      NUMBER(19)     PRIMARY KEY, " +
                    "  balance NUMBER(19, 2)  NOT NULL" +
                    ")");
        }
    }

    // ------------------------------------------------------------------
    // Data reset before each test
    // ------------------------------------------------------------------

    @BeforeEach
    void seedData() throws Exception {
        try (Connection connection = openContainerConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM account");
            statement.executeUpdate("INSERT INTO account VALUES (1, 1000.00)");
            statement.executeUpdate("INSERT INTO account VALUES (2,  500.00)");
        }
    }

    private Connection openContainerConnection() throws Exception {
        return DriverManager.getConnection(oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword());
    }

    // ------------------------------------------------------------------
    // Test 1 – Application context starts successfully
    // ------------------------------------------------------------------

    @Test
    void applicationContextLoads() {
        assertThat(accountService).isNotNull();
        assertThat(jdbcTemplate).isNotNull();
        assertThat(dataSource).isNotNull();
    }

    // ------------------------------------------------------------------
    // Test 2 – Successful transfer commits both updates
    // ------------------------------------------------------------------

    @Test
    void successfulTransferCommitsBothUpdates() {
        BigDecimal amount = new BigDecimal("200.00");

        accountService.transfer(1L, 2L, amount);

        assertThat(accountService.getBalance(1L))
                .isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(accountService.getBalance(2L))
                .isEqualByComparingTo(new BigDecimal("700.00"));
    }

    // ------------------------------------------------------------------
    // Test 3 – Transfer that throws rolls back both updates
    // ------------------------------------------------------------------

    @Test
    void failingTransferRollsBackBothUpdates() {
        // A transfer with amount = 0 triggers IllegalArgumentException inside
        // the @Transactional method → Atomikos rolls back the transaction.
        assertThatThrownBy(() -> accountService.transfer(1L, 2L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        // Balances must be unchanged – both updates were rolled back.
        assertThat(accountService.getBalance(1L))
                .isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(accountService.getBalance(2L))
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ------------------------------------------------------------------
    // Test 4 – Concurrent load: UCP is the only pool
    // ------------------------------------------------------------------

    /**
     * Runs more concurrent transfers than the UCP max pool size (3) to prove:
     * <ul>
     *   <li>UCP queues callers when the pool is exhausted — no extra Atomikos pool.</li>
     *   <li>All transactions complete successfully.</li>
     *   <li>Data remains consistent (total balance is conserved).</li>
     * </ul>
     */
    @Test
    void concurrentTransfersRespectUcpPoolAndRemainConsistent() throws Exception {
        // UCP max pool size = 3 (set in DataSourceConfig / datasource.max-pool-size).
        // We submit 9 concurrent transfers — 3× more than the pool — so UCP must
        // queue 6 callers while the first 3 hold connections.
        int numTransfers = 9;
        BigDecimal amount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(numTransfers);
        List<Future<?>> futures = new ArrayList<>(numTransfers);

        for (int i = 0; i < numTransfers; i++) {
            futures.add(executor.submit(() -> accountService.transfer(1L, 2L, amount)));
        }

        // Collect results; any exception propagates here and fails the test.
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Total balance must be conserved (1000 + 500 = 1500).
        BigDecimal b1 = accountService.getBalance(1L);
        BigDecimal b2 = accountService.getBalance(2L);
        assertThat(b1.add(b2)).isEqualByComparingTo(new BigDecimal("1500.00"));

        // Account 1 should have been debited numTransfers × amount.
        assertThat(b1).isEqualByComparingTo(
                new BigDecimal("1000.00").subtract(amount.multiply(BigDecimal.valueOf(numTransfers))));
    }

    // ------------------------------------------------------------------
    // Test 5 – DataSource bean is AtomikosNonPoolingDataSourceBean
    // ------------------------------------------------------------------

    @Test
    void dataSourceIsAtomikosNonPoolingDataSourceBean() {
        // Must be the non-pooling variant — Atomikos must NOT own a pool.
        assertThat(dataSource).isInstanceOf(AtomikosNonPoolingDataSourceBean.class);
    }

    // ------------------------------------------------------------------
    // Test 6 – Wrapped XA datasource is Oracle UCP PoolXADataSource
    // ------------------------------------------------------------------

    @Test
    void wrappedXaDatasourceIsUcpPoolXADataSource() {
        AtomikosNonPoolingDataSourceBean atomikosDs =
                (AtomikosNonPoolingDataSourceBean) dataSource;

        // The XA datasource must be UCP's PoolXADataSource, confirming that
        // UCP is the single connection pool in the stack.
        assertThat(atomikosDs.getXaDataSource()).isInstanceOf(PoolXADataSource.class);
    }
}
