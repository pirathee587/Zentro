package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Main 10 customer acceptance. Real gateway, Keycloak, S1/S2 and owning-database recounts. */
class CustomerBookingIT extends BackendAcceptanceStack {
  private static String owner;
  private static String foreign;

  @BeforeAll
  static void customers() throws Exception {
    owner = createIsolatedRoleClient("booking-owner", UUID.randomUUID().toString(), "CUSTOMER");
    foreign = createIsolatedRoleClient("booking-foreign", UUID.randomUUID().toString(), "CUSTOMER");
  }

  @Test
  void sameRoleForeignCustomerCannotReadProfileOrDirectReceiptIncludingPdf() throws Exception {
    var profile = call("GET", "/api/v1/users/me", owner, null);
    assertThat(profile.statusCode()).isEqualTo(200);
    String user = JSON.readTree(profile.body()).path("id").asText();
    assertThat(call("GET", "/api/v1/users/" + user, foreign, null).statusCode()).isEqualTo(403);
    var response = book(owner, orderBody(), UUID.randomUUID().toString());
    assertThat(response.statusCode()).isEqualTo(201);
    String id = JSON.readTree(response.body()).path("id").asText();
    for (String suffix : new String[]{"/receipt", "/receipt/pdf"}) {
      String uri = "http://127.0.0.1:18082/api/v1/parcels/" + id + suffix;
      var own = sendRaw("GET", uri, owner, null);
      assertThat(own.statusCode()).isEqualTo(200);
      assertThat(sendRaw("GET", uri, foreign, null).statusCode()).isEqualTo(404);
      if (suffix.endsWith("pdf")) assertThat(own.body()).startsWith("%PDF-");
      System.out.printf("ACCEPTANCE actor=CUSTOMER_OWNER endpoint=S2%s expected=200 actual=200%n", suffix);
      System.out.printf("ACCEPTANCE actor=FOREIGN_CUSTOMER endpoint=S2%s expected=404 actual=404%n", suffix);
    }
  }

  @Test
  void conflictingReplayPreservesOriginalBookingAndSingleOutboxFact() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = orderBody();
    var first = book(owner, body, key);
    assertThat(first.statusCode()).isEqualTo(201);
    String id = JSON.readTree(first.body()).path("id").asText();
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body);
    changed.put("notes", "A conflicting acceptance request");
    assertThat(book(owner, JSON.writeValueAsString(changed), key).statusCode()).isEqualTo(409);
    try (var db = source()) {
      assertThat(count(db, "SELECT count(*) FROM parcel.parcel WHERE idempotency_key = ?", key)).isEqualTo(1);
      assertThat(count(db, "SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id = ?::uuid", id)).isEqualTo(1);
    }
    System.out.println("ACCEPTANCE scenario=booking-conflict expected=409 actual=409 bookings=1 createdFacts=1");
  }

  @Test
  void invalidBookingDoesNotPersist() throws Exception {
    String key = UUID.randomUUID().toString();
    assertThat(book(owner, "{}", key).statusCode()).isEqualTo(422);
    try (var db = source()) {
      assertThat(count(db, "SELECT count(*) FROM parcel.parcel WHERE idempotency_key = ?", key)).isZero();
    }
    System.out.println("ACCEPTANCE scenario=invalid-booking expected=422 actual=422 bookings=0");
  }

  @Test
  void outboxStorageFailureRollsBackBookingAndSameKeyRecovers() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = orderBody();
    try (var db = source(); var ddl = db.createStatement()) {
      // Disposable S2-owned fault injection only; no migration or business rows are altered.
      ddl.execute("CREATE FUNCTION parcel.acceptance_reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'acceptance outbox storage failure'; END $$");
      try {
        ddl.execute("CREATE TRIGGER acceptance_reject_outbox BEFORE INSERT ON parcel.outbox_event FOR EACH ROW EXECUTE FUNCTION parcel.acceptance_reject_outbox()");
        try {
          var rejected = book(owner, body, key);
          // Existing gateway contract sanitizes upstream 5xx to 502; S2's own handler uses 503.
          assertThat(rejected.statusCode()).isEqualTo(502);
          assertThat(rejected.body()).doesNotContain("acceptance outbox storage failure");
          assertThat(count(db, "SELECT count(*) FROM parcel.parcel WHERE idempotency_key = ?", key)).isZero();
        } finally {
          ddl.execute("DROP TRIGGER IF EXISTS acceptance_reject_outbox ON parcel.outbox_event");
        }
      } finally {
        ddl.execute("DROP FUNCTION parcel.acceptance_reject_outbox()");
      }
      var recovered = book(owner, body, key);
      assertThat(recovered.statusCode()).isEqualTo(201);
      String id = JSON.readTree(recovered.body()).path("id").asText();
      assertThat(count(db, "SELECT count(*) FROM parcel.parcel WHERE idempotency_key = ?", key)).isEqualTo(1);
      assertThat(count(db, "SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id = ?::uuid", id)).isEqualTo(1);
      System.out.println("ACCEPTANCE scenario=outbox-storage-rollback expected=502 actual=502 recovery=201 bookings=1 createdFacts=1");
    }
  }

  private static Connection source() throws Exception {
    return DriverManager.getConnection(jdbcUrl("parcel_pricing_db"), "parcel_pricing_user", "parcel-pricing-only");
  }

  private static int count(Connection db, String sql, String value) throws Exception {
    try (var statement = db.prepareStatement(sql)) {
      statement.setString(1, value);
      try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
    }
  }
}
