package com.example.atomikos.config;

import com.atomikos.icatch.jta.UserTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.jta.JtaTransactionManager;

/**
 * Configures Atomikos as the JTA transaction manager.
 *
 * <p>Spring's {@link JtaTransactionManager} delegates begin/commit/rollback to
 * Atomikos, which coordinates the two-phase commit (2PC) protocol across all
 * XA-capable resources enlisted in a transaction.
 *
 * <p>Atomikos is responsible <em>only</em> for XA transaction coordination.
 * It does <em>not</em> maintain a connection pool — that role belongs entirely
 * to Oracle UCP (see {@link DataSourceConfig}).
 *
 * <p>This project uses Atomikos 6.0.1's Jakarta-native artifacts, so
 * {@link UserTransactionManager} can be wired directly into Spring Boot 3's
 * {@link JtaTransactionManager}.
 */
@Configuration
@EnableTransactionManagement
public class TransactionManagerConfig {

    /**
     * Atomikos {@link UserTransactionManager} — the core JTA transaction
     * coordinator.
     *
     * <p>{@code init()} starts the Atomikos transaction service (including the
     * recovery thread); {@code close()} shuts it down gracefully on context close.
     */
    @Bean(initMethod = "init", destroyMethod = "close")
    public UserTransactionManager atomikosUserTransactionManager() {
        UserTransactionManager utm = new UserTransactionManager();
        // Do not force-abort in-progress transactions on JVM exit.
        utm.setForceShutdown(false);
        return utm;
    }

    /**
     * Spring {@link JtaTransactionManager} — bridges Spring's
     * {@code @Transactional} abstraction to Atomikos JTA.
     */
    @Bean
    @DependsOn("atomikosUserTransactionManager")
    public JtaTransactionManager transactionManager(
            UserTransactionManager atomikosUserTransactionManager) {
        JtaTransactionManager tm = new JtaTransactionManager();
        tm.setTransactionManager(atomikosUserTransactionManager);
        tm.setUserTransaction(atomikosUserTransactionManager);
        // Required when using Atomikos with non-default isolation levels.
        tm.setAllowCustomIsolationLevels(true);
        return tm;
    }
}
