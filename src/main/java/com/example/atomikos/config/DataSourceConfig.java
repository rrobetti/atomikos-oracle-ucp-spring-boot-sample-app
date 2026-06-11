package com.example.atomikos.config;

import com.atomikos.jdbc.AtomikosNonPoolingDataSourceBean;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import oracle.ucp.jdbc.PoolXADataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Configures the datasource stack:
 *
 * <pre>
 * Application
 *   → AtomikosNonPoolingDataSourceBean   (XA / JTA coordination — NO Atomikos pool)
 *     → Oracle UCP PoolXADataSource      (THE connection pool)
 *       → oracle.jdbc.xa.client.OracleXADataSource
 *         → Oracle Database
 * </pre>
 *
 * <h3>Why AtomikosNonPoolingDataSourceBean?</h3>
 * <p>{@code AtomikosDataSourceBean} owns its own internal connection pool
 * (min/max/idle settings).  Using it on top of Oracle UCP creates a
 * <em>double-pooling</em> anti-pattern: two stacked pools that interfere with
 * each other's idle eviction, validation, and Oracle-specific features (DRCP,
 * connection labelling, implicit connection cache).
 *
 * <p>{@code AtomikosNonPoolingDataSourceBean} skips Atomikos pooling entirely.
 * On every {@code getConnection()} call it:
 * <ol>
 *   <li>asks UCP {@code PoolXADataSource.getXAConnection()} for a pooled XA
 *       connection,</li>
 *   <li>wraps the {@code XAConnection} with an Atomikos XA-aware proxy that
 *       enlists the connection in the active JTA transaction, and</li>
 *   <li>returns the connection to UCP when the transaction ends.</li>
 * </ol>
 * UCP owns all connection lifecycle decisions (pool sizing, idle eviction,
 * keep-alive, Oracle affinity).
 *
 * <h3>Pool properties</h3>
 * <p>All pool-sizing properties ({@code initialPoolSize}, {@code minPoolSize},
 * {@code maxPoolSize}, {@code connectionWaitTimeout}, etc.) are set on the UCP
 * {@code PoolXADataSource}, <strong>never</strong> on
 * {@code AtomikosNonPoolingDataSourceBean}.
 */
@Configuration
public class DataSourceConfig {

    @Value("${datasource.url}")
    private String url;

    @Value("${datasource.username}")
    private String username;

    @Value("${datasource.password}")
    private String password;

    @Value("${datasource.min-pool-size:1}")
    private int minPoolSize;

    @Value("${datasource.max-pool-size:3}")
    private int maxPoolSize;

    /**
     * Builds the datasource bean.
     *
     * <p>Connection properties ({@code datasource.url / username / password}) are
     * injected from {@code application.yml} at startup and can be overridden by
     * environment variables or, in tests, by
     * {@code @DynamicPropertySource} which sets them from the running
     * Testcontainers Oracle container.
     *
     * <h4>Switching to Oracle in production</h4>
     * Set the following in {@code application.yml} (or as environment variables):
     * <pre>
     * datasource:
     *   url:      jdbc:oracle:thin:@//db-host:1521/XEPDB1
     *   username: app_user
     *   password: secret
     * </pre>
     *
     * Oracle XA privileges required for full crash-recovery support:
     * <pre>
     * GRANT SELECT ON sys.dba_pending_transactions TO app_user;
     * GRANT SELECT ON sys.pending_trans$            TO app_user;
     * GRANT SELECT ON sys.dba_2pc_pending           TO app_user;
     * GRANT EXECUTE ON sys.dbms_xa                  TO app_user;
     * </pre>
     */
    @Bean
    @Primary
    public DataSource dataSource() throws Exception {

        // --- 1. Oracle UCP PoolXADataSource  (THE pool) ----------------------
        //
        // UCP creates and manages a pool of oracle.jdbc.xa.OracleXAConnection
        // objects.  All pool-sizing and eviction policy lives here.
        PoolXADataSource ucp = PoolDataSourceFactory.getPoolXADataSource();

        // Oracle XA driver is the connection factory; UCP delegates new-connection
        // creation to OracleXADataSource.getXAConnection().
        ucp.setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource");
        ucp.setURL(url);
        ucp.setUser(username);
        ucp.setPassword(password);

        // Pool sizing — belongs to UCP, never to AtomikosNonPoolingDataSourceBean.
        ucp.setInitialPoolSize(1);
        ucp.setMinPoolSize(minPoolSize);
        ucp.setMaxPoolSize(maxPoolSize);

        // How long (seconds) a caller blocks when the pool is exhausted.
        ucp.setConnectionWaitTimeout(30);

        // --- 2. Atomikos non-pooling wrapper  (XA / JTA coordination) --------
        //
        // AtomikosNonPoolingDataSourceBean does NOT maintain any internal pool.
        // Setting max/min/poolSize/idleTime/lifetime on this bean would be
        // overridden to no-ops (the setters log a warning and return).
        //
        // Atomikos only:
        //   a) calls ucp.getXAConnection() to borrow from UCP,
        //   b) wraps the XAConnection with an XA-aware proxy, and
        //   c) enlists / delists the resource in the active JTA transaction.
        AtomikosNonPoolingDataSourceBean ds = new AtomikosNonPoolingDataSourceBean();
        ds.setUniqueResourceName("oracle-xa-ucp");
        ds.setXaDataSource(ucp);
        // false = full XA / 2PC protocol (required for genuine XA recovery).
        ds.setLocalTransactionMode(false);

        return ds;
    }
}
