package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** Exercises the real S1 -> S3 -> S2 gRPC POD path and Kafka tracking consumer. */
class DistributedPodWorkflowIT extends BackendAcceptanceStack {
  @Test
  void assignedCourierCompletesParcelThroughServiceAuthenticatedGrpcAndKafka() throws Exception {
    // S3 uses its own remote JWKS decoder. Prime it before the gateway's 30s
    // response timeout can be consumed by the first Keycloak key fetch.
    var warmup = HTTP.send(java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://127.0.0.1:18083/api/v1/dispatch/queue"))
        .timeout(java.time.Duration.ofSeconds(90))
        .header("Authorization", "Bearer " + dispatcherToken)
        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(warmup.statusCode()).isEqualTo(200);

    String parcelBody = orderBody();
    var booked = book(customerToken, parcelBody, UUID.randomUUID().toString());
    assertThat(booked.statusCode()).isEqualTo(201);
    String parcelId = JSON.readTree(booked.body()).path("id").asText();

    String courierSecret = UUID.randomUUID().toString();
    String courierToken = createIsolatedRoleClient("pod-courier-" + UUID.randomUUID(), courierSecret, "COURIER");
    var provisioned = call("POST", "/api/v1/courier/profile/provision", courierToken, null);
    assertThat(provisioned.statusCode()).isEqualTo(200);
    String courierId = JSON.readTree(provisioned.body()).path("id").asText();

    var assigned = call("POST", "/api/v1/dispatch/assignments", dispatcherToken,
        "{\"parcelId\":\"" + parcelId + "\",\"courierId\":\"" + courierId + "\"}");
    assertThat(assigned.statusCode()).isEqualTo(201);

    String otherToken = createIsolatedRoleClient("pod-other-courier-" + UUID.randomUUID(),
        UUID.randomUUID().toString(), "COURIER");
    assertThat(call("POST", "/api/v1/courier/profile/provision", otherToken, null).statusCode()).isEqualTo(200);
    assertThat(call("POST", "/api/v1/courier/deliveries/" + parcelId + "/proof", otherToken,
        "{\"recipientName\":\"Recipient\",\"proofReference\":\"unauthorized\"}").statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/v1/courier/deliveries/" + UUID.randomUUID() + "/proof", courierToken,
        "{\"recipientName\":\"Recipient\",\"proofReference\":\"wrong-assignment\"}").statusCode()).isEqualTo(404);

    assertThat(call("POST", "/api/v1/courier/deliveries/" + parcelId + "/accept", courierToken, null).statusCode()).isEqualTo(200);
    for (String status : new String[]{"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY"}) {
      var update = call("PATCH", "/api/v1/courier/deliveries/" + parcelId + "/status", courierToken,
          "{\"status\":\"" + status + "\",\"note\":\"E2E journey\"}");
      assertThat(update.statusCode()).as("courier status %s: %s", status, update.body()).isEqualTo(200);
    }
    String proofBody = "{\"recipientName\":\"Recipient\",\"notes\":\"Delivered\",\"proofReference\":\"pod-" + parcelId + "\"}";
    var submitted = call("POST", "/api/v1/courier/deliveries/" + parcelId + "/proof", courierToken, proofBody);
    assertThat(submitted.statusCode()).isEqualTo(201);
    var duplicate = call("POST", "/api/v1/courier/deliveries/" + parcelId + "/proof", courierToken, proofBody);
    assertThat(duplicate.statusCode()).isEqualTo(200);

    Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
      try (var db = DriverManager.getConnection(jdbcUrl("parcel_pricing_db"), "parcel_pricing_user", "parcel-pricing-only");
           var query = db.prepareStatement("SELECT status FROM parcel.parcel WHERE id = ?")) {
        query.setObject(1, UUID.fromString(parcelId));
        try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("DELIVERED"); }
      }
      try (var db = DriverManager.getConnection(jdbcUrl("tracking_reporting_db"), "tracking_user", "tracking-only");
           var query = db.prepareStatement("SELECT count(*) FROM tracking.tracking_event WHERE parcel_id = ? AND status = 'DELIVERED'")) {
        query.setObject(1, UUID.fromString(parcelId));
        try (var row = query.executeQuery()) { row.next(); assertThat(row.getInt(1)).isEqualTo(1); }
      }
      try (var db = DriverManager.getConnection(jdbcUrl("notification_integration_db"), "notification_integration_user", "notification-integration-only");
           var query = db.prepareStatement("SELECT count(*) FROM notification.notification_attempt WHERE parcel_id = ? AND parcel_status = 'DELIVERED'")) {
        query.setObject(1, UUID.fromString(parcelId));
        try (var row = query.executeQuery()) { row.next(); assertThat(row.getInt(1)).isEqualTo(1); }
      }
    });
    try (var db = DriverManager.getConnection(jdbcUrl("dispatch_courier_db"), "dispatch_user", "dispatch-only");
         var query = db.prepareStatement("SELECT completion_state FROM dispatch.proof_of_delivery WHERE parcel_id = ?")) {
      query.setObject(1, UUID.fromString(parcelId));
      try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("COMPLETED"); }
    }

    var secondBooking = book(customerToken, orderBody(), UUID.randomUUID().toString());
    assertThat(secondBooking.statusCode()).isEqualTo(201);
    String retryParcelId = JSON.readTree(secondBooking.body()).path("id").asText();
    assertThat(call("POST", "/api/v1/dispatch/assignments", dispatcherToken,
        "{\"parcelId\":\"" + retryParcelId + "\",\"courierId\":\"" + courierId + "\"}").statusCode()).isEqualTo(201);
    assertThat(call("POST", "/api/v1/courier/deliveries/" + retryParcelId + "/accept", courierToken, null).statusCode()).isEqualTo(200);
    for (String status : new String[]{"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY"}) {
      assertThat(call("PATCH", "/api/v1/courier/deliveries/" + retryParcelId + "/status", courierToken,
          "{\"status\":\"" + status + "\",\"note\":\"E2E retry journey\"}").statusCode()).isEqualTo(200);
    }
    stop(s2);
    var pendingProof = call("POST", "/api/v1/courier/deliveries/" + retryParcelId + "/proof", courierToken,
        "{\"recipientName\":\"Recipient\",\"proofReference\":\"retry-" + retryParcelId + "\"}");
    assertThat(pendingProof.statusCode()).isEqualTo(201);
    Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
      try (var db = DriverManager.getConnection(jdbcUrl("dispatch_courier_db"), "dispatch_user", "dispatch-only");
           var query = db.prepareStatement("SELECT attempts, completion_state, last_error_code FROM dispatch.proof_of_delivery WHERE parcel_id = ?")) {
        query.setObject(1, UUID.fromString(retryParcelId));
        try (var row = query.executeQuery()) {
          assertThat(row.next()).isTrue();
          assertThat(row.getInt("attempts")).isGreaterThanOrEqualTo(1);
          assertThat(row.getString("completion_state")).isEqualTo("PENDING");
          assertThat(row.getString("last_error_code")).isEqualTo("PARCEL_SERVICE_UNAVAILABLE");
        }
      }
    });
    restartParcel();
    Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
      try (var db = DriverManager.getConnection(jdbcUrl("parcel_pricing_db"), "parcel_pricing_user", "parcel-pricing-only");
           var query = db.prepareStatement("SELECT status FROM parcel.parcel WHERE id = ?")) {
        query.setObject(1, UUID.fromString(retryParcelId));
        try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("DELIVERED"); }
      }
      try (var db = DriverManager.getConnection(jdbcUrl("dispatch_courier_db"), "dispatch_user", "dispatch-only");
           var query = db.prepareStatement("SELECT completion_state FROM dispatch.proof_of_delivery WHERE parcel_id = ?")) {
        query.setObject(1, UUID.fromString(retryParcelId));
        try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("COMPLETED"); }
      }
    });
  }
}
