package com.example.atomikos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Simple account-transfer service backed by {@link JdbcTemplate}.
 *
 * <p>All write operations run inside a JTA transaction coordinated by Atomikos.
 * When {@link #transfer} is called, Atomikos enlists the UCP XA connection in a
 * distributed transaction and commits (or rolls back) atomically.
 *
 * <p>The {@code account} table schema:
 * <pre>
 * CREATE TABLE account (
 *     id      BIGINT         PRIMARY KEY,
 *     balance DECIMAL(19, 2) NOT NULL
 * );
 * </pre>
 */
@Service
public class AccountService {

    private final JdbcTemplate jdbcTemplate;

    public AccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Transfers {@code amount} from account {@code fromId} to account {@code toId}
     * in a single JTA transaction.
     *
     * <p>Both the debit and the credit are performed inside one atomic unit of work.
     * If any exception is thrown, Atomikos rolls back both updates.
     *
     * @param fromId account to debit
     * @param toId   account to credit
     * @param amount positive amount to transfer
     * @throws IllegalArgumentException if {@code amount} is not positive
     */
    @Transactional
    public void transfer(long fromId, long toId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive: " + amount);
        }
        jdbcTemplate.update(
                "UPDATE account SET balance = balance - ? WHERE id = ?",
                amount, fromId);
        jdbcTemplate.update(
                "UPDATE account SET balance = balance + ? WHERE id = ?",
                amount, toId);
    }

    /**
     * Returns the current balance for the given account id.
     *
     * @param id account identifier
     * @return current balance
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(long id) {
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM account WHERE id = ?",
                BigDecimal.class,
                id);
        if (balance == null) {
            throw new IllegalStateException("No account found with id: " + id);
        }
        return balance;
    }
}
