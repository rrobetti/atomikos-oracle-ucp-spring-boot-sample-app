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
 * <h3>javax → jakarta bridge</h3>
 * <p>Atomikos 6.0.1 implements {@code javax.transaction.TransactionManager} while
 * Spring Boot 3's {@code JtaTransactionManager} requires
 * {@code jakarta.transaction.TransactionManager}.  {@link AtomikosTransactionBridge}
 * is a thin type-system shim that converts between the two namespaces without
 * altering any transaction semantics.
 */
@Configuration
@EnableTransactionManagement
public class TransactionManagerConfig {

    /**
     * Atomikos {@link UserTransactionManager} — the core JTA transaction
     * coordinator (javax namespace).
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
     * {@link AtomikosTransactionBridge} adapts Atomikos's {@code javax.transaction.*}
     * interfaces to the {@code jakarta.transaction.*} interfaces required by Spring 6.
     */
    @Bean
    public AtomikosTransactionBridge atomikosTransactionBridge(
            UserTransactionManager atomikosUserTransactionManager) {
        return new AtomikosTransactionBridge(atomikosUserTransactionManager);
    }

    /**
     * Spring {@link JtaTransactionManager} — bridges Spring's
     * {@code @Transactional} abstraction to Atomikos JTA via the
     * {@link AtomikosTransactionBridge}.
     */
    @Bean
    @DependsOn("atomikosUserTransactionManager")
    public JtaTransactionManager transactionManager(AtomikosTransactionBridge bridge) {
        JtaTransactionManager tm = new JtaTransactionManager();
        // Both interfaces are satisfied by the same bridge instance.
        tm.setTransactionManager(bridge);
        tm.setUserTransaction(bridge);
        // Required when using Atomikos with non-default isolation levels.
        tm.setAllowCustomIsolationLevels(true);
        return tm;
    }
}
