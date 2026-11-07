package com.zentro.parcelpricing.integration.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only database faults for the outbox atomicity tests (ZD-062). Each fault is a real PostgreSQL trigger that
 * raises an error, so the application's own transactions fail exactly as they would on a real database problem;
 * no production code is changed or flagged. Use in try-with-resources so the trigger is always removed.
 *
 * <ul>
 *   <li>{@link #rejectInsert}: the INSERT into the table fails immediately.</li>
 *   <li>{@link #rejectAtCommit}: the INSERT succeeds, but the transaction fails when it COMMITs (deferred constraint
 *       trigger). This is the strictest atomicity check: every statement ran, yet nothing may be kept.</li>
 *   <li>{@link #rejectPublishedMark}: setting {@code published_at} on an outbox row fails, as if the publisher stopped
 *       after Kafka confirmed the send but before its own transaction committed.</li>
 * </ul>
 * Never use against the shared database; the tests run on Testcontainers PostgreSQL only.
 */
public final class OutboxFaultTriggers implements AutoCloseable {
  public static final String MESSAGE = "injected failure (test)";

  private final JdbcTemplate jdbc;
  private final String table;
  private final String name;

  private OutboxFaultTriggers(JdbcTemplate jdbc, String table, String name) {
    this.jdbc = jdbc;
    this.table = table;
    this.name = name;
  }

  /** Every INSERT into {@code table} (for example "parcel.outbox_event") fails. */
  public static OutboxFaultTriggers rejectInsert(JdbcTemplate jdbc, String table) {
    OutboxFaultTriggers fault = create(jdbc, table);
    jdbc.execute("CREATE TRIGGER " + fault.name + " BEFORE INSERT ON " + table
        + " FOR EACH ROW EXECUTE FUNCTION parcel." + fault.name + "()");
    return fault;
  }

  /** INSERTs into {@code table} succeed, but any transaction that inserted a row fails at COMMIT. */
  public static OutboxFaultTriggers rejectAtCommit(JdbcTemplate jdbc, String table) {
    OutboxFaultTriggers fault = create(jdbc, table);
    jdbc.execute("CREATE CONSTRAINT TRIGGER " + fault.name + " AFTER INSERT ON " + table
        + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION parcel." + fault.name + "()");
    return fault;
  }

  /** Marking an outbox row as published fails (the send itself is not affected). */
  public static OutboxFaultTriggers rejectPublishedMark(JdbcTemplate jdbc) {
    OutboxFaultTriggers fault = create(jdbc, "parcel.outbox_event");
    jdbc.execute("CREATE TRIGGER " + fault.name + " BEFORE UPDATE ON parcel.outbox_event FOR EACH ROW"
        + " WHEN (OLD.published_at IS NULL AND NEW.published_at IS NOT NULL)"
        + " EXECUTE FUNCTION parcel." + fault.name + "()");
    return fault;
  }

  private static OutboxFaultTriggers create(JdbcTemplate jdbc, String table) {
    String name = "test_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    jdbc.execute("CREATE FUNCTION parcel." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$"
        + " BEGIN RAISE EXCEPTION '" + MESSAGE + "'; END; $$");
    return new OutboxFaultTriggers(jdbc, table, name);
  }

  /** Removes the trigger and its function. */
  @Override
  public void close() {
    jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
    jdbc.execute("DROP FUNCTION IF EXISTS parcel." + name + "()");
  }
}
