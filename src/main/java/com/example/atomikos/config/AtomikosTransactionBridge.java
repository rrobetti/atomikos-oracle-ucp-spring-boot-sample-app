package com.example.atomikos.config;

import com.atomikos.icatch.jta.UserTransactionManager;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;

/**
 * Bridges Atomikos 6.0.1 ({@code javax.transaction.*}) to Spring Boot 3's
 * required ({@code jakarta.transaction.*}) interfaces.
 *
 * <h3>Why this bridge exists</h3>
 * <p>Atomikos 6.0.1 was compiled against
 * {@code jakarta.transaction:jakarta.transaction-api:1.3.3}, which shipped
 * the <em>old</em> {@code javax.transaction.*} package namespace despite the
 * {@code jakarta.*} Maven coordinates.  Spring Boot 3.x's dependency-management
 * BOM overrides that artefact to version 2.0.x, which completed the Jakarta EE 9
 * namespace rename to {@code jakarta.transaction.*}.  Consequently Atomikos's
 * {@code UserTransactionManager} implements {@code javax.transaction.TransactionManager}
 * while Spring 6's {@code JtaTransactionManager} requires
 * {@code jakarta.transaction.TransactionManager} — two incompatible interfaces at
 * the Java type system level.
 *
 * <p>This class implements both {@link TransactionManager} and {@link UserTransaction}
 * from the jakarta namespace, delegating every call to the Atomikos
 * {@link UserTransactionManager} object and converting exception types between the
 * two namespaces.  No behaviour is added or changed; this is a pure type-system
 * shim.
 *
 * <h3>What this bridge does NOT cover</h3>
 * <ul>
 *   <li>{@link Transaction#enlistResource} / {@link Transaction#delistResource} —
 *       Atomikos's {@code AtomikosNonPoolingDataSourceBean} enlists XA resources
 *       internally; Spring's {@code JtaTransactionManager} never calls these methods
 *       directly on the {@code Transaction} object.</li>
 * </ul>
 */
public class AtomikosTransactionBridge implements TransactionManager, UserTransaction {

    private final UserTransactionManager delegate;

    public AtomikosTransactionBridge(UserTransactionManager delegate) {
        this.delegate = delegate;
    }

    // -------------------------------------------------------------------------
    // Methods shared by TransactionManager and UserTransaction
    // -------------------------------------------------------------------------

    @Override
    public void begin() throws NotSupportedException, SystemException {
        try {
            delegate.begin();
        } catch (javax.transaction.NotSupportedException e) {
            throw new NotSupportedException(e.getMessage());
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public void commit() throws RollbackException, HeuristicMixedException,
            HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
        try {
            delegate.commit();
        } catch (javax.transaction.RollbackException e) {
            RollbackException re = new RollbackException(e.getMessage());
            re.initCause(e);
            throw re;
        } catch (javax.transaction.HeuristicMixedException e) {
            HeuristicMixedException hme = new HeuristicMixedException(e.getMessage());
            hme.initCause(e);
            throw hme;
        } catch (javax.transaction.HeuristicRollbackException e) {
            HeuristicRollbackException hre = new HeuristicRollbackException(e.getMessage());
            hre.initCause(e);
            throw hre;
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public void rollback() throws IllegalStateException, SecurityException, SystemException {
        try {
            delegate.rollback();
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public void setRollbackOnly() throws IllegalStateException, SystemException {
        try {
            delegate.setRollbackOnly();
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public int getStatus() throws SystemException {
        try {
            return delegate.getStatus();
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public void setTransactionTimeout(int seconds) throws SystemException {
        try {
            delegate.setTransactionTimeout(seconds);
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    // -------------------------------------------------------------------------
    // TransactionManager-only methods
    // -------------------------------------------------------------------------

    @Override
    public Transaction getTransaction() throws SystemException {
        try {
            javax.transaction.Transaction javaxTx = delegate.getTransaction();
            return javaxTx == null ? null : new TransactionAdapter(javaxTx);
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public Transaction suspend() throws SystemException {
        try {
            javax.transaction.Transaction javaxTx = delegate.suspend();
            return javaxTx == null ? null : new TransactionAdapter(javaxTx);
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    @Override
    public void resume(Transaction tobj)
            throws InvalidTransactionException, IllegalStateException, SystemException {
        javax.transaction.Transaction javaxTx = null;
        if (tobj instanceof TransactionAdapter adapter) {
            javaxTx = adapter.getDelegate();
        } else if (tobj != null) {
            throw new InvalidTransactionException(
                    "Cannot resume: unrecognised transaction type " + tobj.getClass().getName());
        }
        try {
            delegate.resume(javaxTx);
        } catch (javax.transaction.InvalidTransactionException e) {
            InvalidTransactionException ite = new InvalidTransactionException(e.getMessage());
            ite.initCause(e);
            throw ite;
        } catch (javax.transaction.SystemException e) {
            throw toJakarta(e);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static SystemException toJakarta(javax.transaction.SystemException e) {
        SystemException se = new SystemException(e.errorCode);
        se.initCause(e);
        return se;
    }

    // -------------------------------------------------------------------------
    // Inner class: javax.transaction.Transaction → jakarta.transaction.Transaction
    // -------------------------------------------------------------------------

    /**
     * Wraps a {@code javax.transaction.Transaction} obtained from Atomikos as a
     * {@code jakarta.transaction.Transaction} expected by Spring.
     *
     * <p>Spring uses the {@code Transaction} object primarily for:
     * <ol>
     *   <li>Suspend / resume bookkeeping (the wrapped reference is passed back to
     *       {@link AtomikosTransactionBridge#resume}).</li>
     *   <li>Registering Spring's internal completion {@link Synchronization} so that
     *       {@code @Transactional} after-commit / after-rollback hooks fire.</li>
     * </ol>
     *
     * <p>{@link #enlistResource} and {@link #delistResource} intentionally throw
     * {@link UnsupportedOperationException}: resource enlistment is handled
     * internally by Atomikos's {@code AtomikosNonPoolingDataSourceBean} and Spring's
     * {@code JtaTransactionManager} never calls those methods directly.
     */
    static final class TransactionAdapter implements Transaction {

        private final javax.transaction.Transaction delegate;

        TransactionAdapter(javax.transaction.Transaction delegate) {
            this.delegate = delegate;
        }

        javax.transaction.Transaction getDelegate() {
            return delegate;
        }

        @Override
        public void commit() throws RollbackException, HeuristicMixedException,
                HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
            try {
                delegate.commit();
            } catch (javax.transaction.RollbackException e) {
                RollbackException re = new RollbackException(e.getMessage());
                re.initCause(e);
                throw re;
            } catch (javax.transaction.HeuristicMixedException e) {
                HeuristicMixedException hme = new HeuristicMixedException(e.getMessage());
                hme.initCause(e);
                throw hme;
            } catch (javax.transaction.HeuristicRollbackException e) {
                HeuristicRollbackException hre = new HeuristicRollbackException(e.getMessage());
                hre.initCause(e);
                throw hre;
            } catch (javax.transaction.SystemException e) {
                throw toJakarta(e);
            }
        }

        /**
         * Not delegated – Atomikos handles XA resource enlistment internally via
         * {@code AtomikosNonPoolingDataSourceBean.getConnection()}.
         */
        @Override
        public boolean enlistResource(javax.transaction.xa.XAResource xaRes)
                throws RollbackException, IllegalStateException, SystemException {
            throw new UnsupportedOperationException(
                    "enlistResource is not supported via the javax-jakarta bridge; "
                    + "Atomikos handles XA enlistment internally.");
        }

        /** @see #enlistResource */
        @Override
        public boolean delistResource(javax.transaction.xa.XAResource xaRes, int flag)
                throws IllegalStateException, SystemException {
            throw new UnsupportedOperationException(
                    "delistResource is not supported via the javax-jakarta bridge; "
                    + "Atomikos handles XA delistment internally.");
        }

        @Override
        public int getStatus() throws SystemException {
            try {
                return delegate.getStatus();
            } catch (javax.transaction.SystemException e) {
                throw toJakarta(e);
            }
        }

        /**
         * Wraps the jakarta {@link Synchronization} in a
         * {@link SynchronizationAdapter} so it can be registered with the
         * underlying {@code javax.transaction.Transaction}.
         *
         * <p>Spring registers its own completion hooks here so that
         * {@code @TransactionalEventListener} and
         * {@code TransactionSynchronizationManager} callbacks fire correctly after
         * commit or rollback.
         */
        @Override
        public void registerSynchronization(Synchronization sync)
                throws RollbackException, IllegalStateException, SystemException {
            try {
                delegate.registerSynchronization(new SynchronizationAdapter(sync));
            } catch (javax.transaction.RollbackException e) {
                RollbackException re = new RollbackException(e.getMessage());
                re.initCause(e);
                throw re;
            } catch (javax.transaction.SystemException e) {
                throw toJakarta(e);
            }
        }

        @Override
        public void rollback() throws IllegalStateException, SystemException {
            try {
                delegate.rollback();
            } catch (javax.transaction.SystemException e) {
                throw toJakarta(e);
            }
        }

        @Override
        public void setRollbackOnly() throws IllegalStateException, SystemException {
            try {
                delegate.setRollbackOnly();
            } catch (javax.transaction.SystemException e) {
                throw toJakarta(e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inner class: jakarta.transaction.Synchronization → javax.transaction.Synchronization
    // -------------------------------------------------------------------------

    /**
     * Wraps a {@code jakarta.transaction.Synchronization} as a
     * {@code javax.transaction.Synchronization} for registration with Atomikos's
     * internal {@code javax.transaction.Transaction}.
     *
     * <p>The {@code beforeCompletion()} and {@code afterCompletion(int)} signatures
     * and status-code constants are identical in both namespaces, so no conversion
     * is needed for the callback payloads.
     */
    private static final class SynchronizationAdapter
            implements javax.transaction.Synchronization {

        private final Synchronization delegate;

        SynchronizationAdapter(Synchronization delegate) {
            this.delegate = delegate;
        }

        @Override
        public void beforeCompletion() {
            delegate.beforeCompletion();
        }

        @Override
        public void afterCompletion(int status) {
            delegate.afterCompletion(status);
        }
    }
}
