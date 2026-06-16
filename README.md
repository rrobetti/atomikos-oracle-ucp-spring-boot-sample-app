# Atomikos + Oracle UCP + Spring Boot 3 — Sample App

A minimal Spring Boot 3 project demonstrating how to wire
**Oracle Universal Connection Pool (UCP)** with **Atomikos 6.0.1** using
the new `AtomikosNonPoolingDataSourceBean`, avoiding the double-pooling
problem present in Atomikos 6.0.0 and earlier.

---

## Architecture

```
Application
  → AtomikosNonPoolingDataSourceBean   (XA / JTA coordination — NO Atomikos pool)
    → Oracle UCP PoolXADataSource      (THE connection pool)
      → oracle.jdbc.xa.client.OracleXADataSource
        → Oracle Database
```

---

## Why `AtomikosNonPoolingDataSourceBean` instead of `AtomikosDataSourceBean`?

| Bean | Internal pool? | Use case |
|---|---|---|
| `AtomikosDataSourceBean` | Yes — Atomikos manages a pool | Atomikos is both the XA wrapper **and** the pool |
| `AtomikosNonPoolingDataSourceBean` | **No** | An external pool (e.g. UCP) owns all connections |

`AtomikosDataSourceBean` maintains its own internal connection pool (min/max/idle settings).
When the underlying `XADataSource` is *also* a pool (like Oracle UCP), this creates
**two stacked pools**:

- Atomikos pool acquires a UCP connection and holds it until the Atomikos pool's own
  idle/lifetime policy decides to return it.
- UCP cannot efficiently reclaim or validate connections because Atomikos is sitting
  in the way.
- This was a known issue in Atomikos 6.0.0 and earlier — `AtomikosNonPoolingDataSourceBean`
  was introduced in 6.0.1 precisely to solve this.

`AtomikosNonPoolingDataSourceBean` removes the Atomikos pool layer entirely:

1. On every `getConnection()` call Atomikos calls `ucpPool.getXAConnection()`.
2. UCP lends a connection from its pool.
3. Atomikos wraps the `XAConnection` with an XA-aware proxy and enlists it in the
   JTA transaction.
4. When the transaction ends Atomikos returns the connection to UCP.

UCP controls all pool lifecycle: initial/min/max size, idle eviction, connection
validation, statement caching, etc.

---

## Why UCP must be the only pool

Oracle UCP is designed to manage Oracle XA connections natively.  It understands
Oracle-specific keep-alive probes, labelling, connection affinity and DRCP.
A double-pooling setup interferes with these features and can lead to:

- Stale connections held by Atomikos that UCP has already evicted.
- Incorrect XA branch handling if a connection is reused across transactions.
- Performance overhead from two layers of pool bookkeeping.

Using `AtomikosNonPoolingDataSourceBean` ensures UCP is the single source of truth
for connection management.

---

## How to switch from the test setup to a real Oracle instance

The datasource URL, username and password are externalised as properties:

```yaml
# application.yml
datasource:
  url:      jdbc:oracle:thin:@//db-host:1521/XEPDB1
  username: app_user
  password: secret
```

You can also supply them as environment variables or JVM system properties:

```bash
java -jar target/atomikos-oracle-ucp-sample-0.0.1-SNAPSHOT.jar \
     --datasource.url=jdbc:oracle:thin:@//db-host:1521/XEPDB1 \
     --datasource.username=app_user \
     --datasource.password=your_password_here
```

Make sure `ojdbc11` (or `ojdbc8`) and `ucp` are on the classpath — they are
already included in `pom.xml`.

---

## Oracle XA privileges required for production recovery

In real Oracle XA scenarios Atomikos may need to perform in-doubt transaction
recovery after a crash.  The connecting user must hold:

```sql
GRANT SELECT ON sys.dba_pending_transactions TO app_user;
GRANT SELECT ON sys.pending_trans$            TO app_user;
GRANT SELECT ON sys.dba_2pc_pending           TO app_user;
GRANT EXECUTE ON sys.dbms_xa                  TO app_user;
```

Without these privileges the Atomikos recovery thread will log warnings and
in-doubt XA branches may be left open.

In the integration tests, `src/test/resources/jta.properties` sets
`com.atomikos.icatch.enable_logging=false` to disable the recovery scan so these
grants are not required inside the Testcontainers Oracle container.

---

## Integration tests with Oracle Testcontainers

The integration tests spin up a real Oracle XE 21c database using
[Testcontainers](https://www.testcontainers.org/).  A real Oracle instance is
required to exercise `OracleXADataSource` and Oracle UCP's XA connection pool —
an in-memory substitute cannot fully prove this behaviour.

**Prerequisites**: Docker must be available on the machine running `mvn test`.

```bash
mvn test
```

`@DynamicPropertySource` injects the container's JDBC URL, username and password
into the Spring environment before the application context starts, so the UCP
`PoolXADataSource` points at the running container automatically.

The tests prove:
1. The application context starts correctly.
2. A successful transfer commits both debit and credit updates.
3. A transfer that throws an exception rolls back both updates.
4. Under concurrent load (9 threads vs. UCP max pool of 3) all transactions
   complete and data remains consistent — UCP queues callers, Atomikos has no pool.
5. The injected `DataSource` bean is an `AtomikosNonPoolingDataSourceBean`.
6. The wrapped XA datasource inside Atomikos is a UCP `PoolXADataSource`.

---

## Building

```bash
mvn compile       # compile only (no Docker required)
mvn test          # compile + run integration tests (Docker required)
mvn package -DskipTests   # build JAR without tests
```

---

## Project layout

```
src/main/java/com/example/atomikos/
├── AtomikosUcpSampleApp.java              Spring Boot entry point
├── config/
│   ├── DataSourceConfig.java              UCP PoolXADataSource + AtomikosNonPoolingDataSourceBean
│   └── TransactionManagerConfig.java      Atomikos UserTransactionManager + JtaTransactionManager
└── service/
    └── AccountService.java                @Transactional transfer() via JdbcTemplate

src/test/java/com/example/atomikos/
└── AccountServiceIntegrationTest.java     6 integration tests (Oracle Testcontainers)

src/test/resources/
└── jta.properties                         Disables Atomikos XA recovery log for tests
```