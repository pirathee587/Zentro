package com.zentro.parcelpricing.outbox.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelBooking;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository.LockedParcel;
import com.zentro.parcelpricing.parcel.service.ParcelStatusService;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.parcel.service.TrackingReferenceGenerator;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Producer contract test: the events S2 actually writes to the outbox must validate against the shared schema
 * files in contracts/events and must not carry personal data. Consumers (S4, S5) rely on these files.
 */
class ParcelEventContractTest {
  private static final Path CONTRACTS = Path.of("..", "..", "contracts", "events").toAbsolutePath().normalize();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String TRACKING_REF = "ZT-ABCDEFGH12";
  private static final List<String> PERSONAL_DATA = List.of(
      "Nimal Perera", "Kamala Silva", "0771234567", "712345678", "Galle Road", "Temple Street",
      "customer-sub-1", "dispatcher-sub-9", "Left at the gate");

  private static JsonSchema statusSchema;
  private static JsonSchema createdSchema;

  @BeforeAll
  static void loadSchemas() {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    statusSchema = factory.getSchema(
        SchemaLocation.of(CONTRACTS.resolve("parcel-status-updated.schema.json").toUri().toString()), config);
    createdSchema = factory.getSchema(
        SchemaLocation.of(CONTRACTS.resolve("parcel-created.schema.json").toUri().toString()), config);
  }

  private static Set<ValidationMessage> errors(JsonSchema schema, JsonNode event) {
    return schema.validate(event);
  }

  /** Runs the real status service with a mocked repository and returns the event JSON it wrote. */
  private static String statusEvent(ParcelStatus from, ParcelStatus to, String reason) {
    ParcelStatusRepository repository = mock(ParcelStatusRepository.class);
    when(repository.lockForUpdate(PARCEL_ID)).thenReturn(Optional.of(new LockedParcel(PARCEL_ID, TRACKING_REF, from, 3)));
    when(repository.updateStatus(any(), any(), anyLong(), any())).thenReturn(4L);
    new ParcelStatusService(repository, MAPPER)
        .updateStatus(PARCEL_ID, to, reason, "Left at the gate", "dispatcher-sub-9", "DISPATCHER");
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(repository).insertOutboxEvent(any(), eq(PARCEL_ID), eq("parcel.status.updated"), json.capture(), any());
    return json.getValue();
  }

  /** Runs the real booking service with mocked collaborators and returns the parcel.created event JSON. */
  private static String createdEvent(OffsetDateTime createdAt) {
    PricingService pricing = mock(PricingService.class);
    ParcelBookingRepository bookings = mock(ParcelBookingRepository.class);
    TrackingReferenceGenerator refs = mock(TrackingReferenceGenerator.class);
    ParcelStatusRepository events = mock(ParcelStatusRepository.class);
    when(pricing.estimateFee(any())).thenReturn(new FeeEstimateResponse(new BigDecimal("1072.00"), "LKR 1,072", "LKR", 3,
        new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), new BigDecimal("120.00"), new BigDecimal("402.00"), null)));
    when(refs.next()).thenReturn(TRACKING_REF);
    when(bookings.insert(any())).thenAnswer(inv -> {
      ParcelBooking d = inv.getArgument(0);
      return new ParcelBooking(PARCEL_ID, d.trackingRef(), d.customerSubject(), d.sender(), d.recipient(), d.weightKg(),
          d.size(), d.category(), d.description(), d.destinationType(), d.express(), d.notes(), d.fee(), d.currency(),
          d.estimatedDays(), d.status(), d.idempotencyKey(), d.requestHash(), createdAt, createdAt);
    });

    new ParcelBookingService(pricing, bookings, refs, events, MAPPER, mock(PlatformTransactionManager.class))
        .book("customer-sub-1", new CreateParcelBookingRequest(
            new CreateParcelBookingRequest.Contact("Nimal Perera", "0771234567", "12 Galle Road, Colombo 03", "Colombo"),
            new CreateParcelBookingRequest.Contact("Kamala Silva", "+94712345678", "45 Temple Street, Kandy", "Kandy"),
            new CreateParcelBookingRequest.ParcelDetails(new BigDecimal("2"), "Small Box", ParcelSize.SMALL, "Books"),
            DestinationType.OTHER_DISTRICT, false, "Call before delivery"), "idem-1");

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(events).insertOutboxEvent(any(), eq(PARCEL_ID), eq("parcel.created"), json.capture(), any());
    return json.getValue();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "CREATED, PICKED_UP, ",
      "PICKED_UP, IN_TRANSIT, ",
      "IN_TRANSIT, OUT_FOR_DELIVERY, ",
      "OUT_FOR_DELIVERY, DELIVERED, ",
      "PICKED_UP, FAILED, Recipient not available",
      "OUT_FOR_DELIVERY, FAILED, Address not found"
  })
  @DisplayName("Every parcel.status.updated event the service writes matches the schema")
  void statusEventsMatchSchema(ParcelStatus from, ParcelStatus to, String reason) throws Exception {
    String json = statusEvent(from, to, reason);

    assertThat(errors(statusSchema, MAPPER.readTree(json))).isEmpty();
    assertThat(PERSONAL_DATA).noneMatch(json::contains);
  }

  @Test
  @DisplayName("The parcel.created event the booking service writes matches the schema, also on a whole minute")
  void createdEventMatchesSchema() throws Exception {
    for (OffsetDateTime at : List.of(OffsetDateTime.parse("2026-11-03T10:15:30.123456Z"),
        OffsetDateTime.parse("2026-11-03T10:15:00Z"))) {
      String json = createdEvent(at);

      assertThat(errors(createdSchema, MAPPER.readTree(json))).isEmpty();
      assertThat(PERSONAL_DATA).noneMatch(json::contains);
      assertThat(json).doesNotContain("idem-1", "Books", "Call before delivery");
    }
  }

  @Test
  @DisplayName("Schema rejects FAILED without a failure reason, and a failure reason on any other status")
  void failureReasonRule() throws Exception {
    ObjectNode failed = (ObjectNode) MAPPER.readTree(statusEvent(ParcelStatus.PICKED_UP, ParcelStatus.FAILED, "Lost"));
    ((ObjectNode) failed.get("payload")).remove("failureReason");
    assertThat(errors(statusSchema, failed)).isNotEmpty();

    ObjectNode delivered = (ObjectNode) MAPPER.readTree(
        statusEvent(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, null));
    ((ObjectNode) delivered.get("payload")).put("failureReason", "should not be here");
    assertThat(errors(statusSchema, delivered)).isNotEmpty();
  }

  @Test
  @DisplayName("Schema rejects personal-data fields, unknown statuses, extra envelope fields and the wrong event type")
  void schemaRejectsInvalidEvents() throws Exception {
    String valid = statusEvent(ParcelStatus.CREATED, ParcelStatus.PICKED_UP, null);
    List<ObjectNode> invalid = new ArrayList<>();

    ObjectNode withName = (ObjectNode) MAPPER.readTree(valid);
    ((ObjectNode) withName.get("payload")).put("recipientName", "Kamala Silva");
    invalid.add(withName);

    ObjectNode withPhone = (ObjectNode) MAPPER.readTree(valid);
    ((ObjectNode) withPhone.get("payload")).put("recipientPhone", "0771234567");
    invalid.add(withPhone);

    ObjectNode badStatus = (ObjectNode) MAPPER.readTree(valid);
    ((ObjectNode) badStatus.get("payload")).put("status", "LOST");
    invalid.add(badStatus);

    ObjectNode extraEnvelope = (ObjectNode) MAPPER.readTree(valid);
    extraEnvelope.put("customerId", "customer-sub-1");
    invalid.add(extraEnvelope);

    ObjectNode wrongType = (ObjectNode) MAPPER.readTree(valid);
    wrongType.put("eventType", "parcel.created");
    invalid.add(wrongType);

    ObjectNode badTime = (ObjectNode) MAPPER.readTree(valid);
    badTime.put("occurredAt", "2026-11-03T10:15Z");
    invalid.add(badTime);

    assertThat(errors(statusSchema, MAPPER.readTree(valid))).isEmpty();
    for (ObjectNode event : invalid) {
      assertThat(errors(statusSchema, event)).as(event.toString()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("Event payloads contain only the documented fields")
  void payloadFieldsAreExactlyTheDocumentedOnes() throws Exception {
    List<String> statusFields = new ArrayList<>();
    MAPPER.readTree(statusEvent(ParcelStatus.PICKED_UP, ParcelStatus.FAILED, "Lost"))
        .get("payload").fieldNames().forEachRemaining(statusFields::add);
    assertThat(statusFields).containsExactlyInAnyOrder(
        "trackingRef", "previousStatus", "status", "actorRole", "occurredAt", "failureReason");

    List<String> createdFields = new ArrayList<>();
    MAPPER.readTree(createdEvent(OffsetDateTime.parse("2026-11-03T10:15:30Z")))
        .get("payload").fieldNames().forEachRemaining(createdFields::add);
    assertThat(createdFields).containsExactlyInAnyOrder("trackingRef", "status", "source", "occurredAt");
  }
}
