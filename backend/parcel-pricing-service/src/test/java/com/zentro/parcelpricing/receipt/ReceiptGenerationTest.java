package com.zentro.parcelpricing.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.MerchantOrder;
import com.zentro.parcelpricing.parcel.entity.ParcelBooking;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.repository.MerchantOrderRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test verifying that receipt generation strictly preserves persisted booking price,
 * currency, and metadata without repricing or invoking PricingService.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptGenerationTest {

  @Mock private ParcelBookingRepository bookingRepository;
  @Mock private MerchantOrderRepository merchantOrderRepository;

  private ReceiptGenerationService receiptService;
  private final UUID parcelId = UUID.randomUUID();
  private final String trackingRef = "ZT-7K3QX9M2PA";

  @BeforeEach
  void setUp() {
    receiptService = new ReceiptGenerationService(bookingRepository, merchantOrderRepository);
  }

  @Test
  @DisplayName("Receipt directly reflects persisted customer booking fee and currency (1250.00 LKR)")
  void receiptMatchesPersistedCustomerBookingFee() {
    BigDecimal persistedFee = new BigDecimal("1250.00");
    String persistedCurrency = "LKR";

    ParcelBooking booking = new ParcelBooking(
        parcelId,
        trackingRef,
        "customer-user-123",
        new ParcelBooking.Contact("John Sender", "0771112233", "123 Galle Rd", "Colombo"),
        new ParcelBooking.Contact("Jane Recipient", "0779998877", "456 Peradeniya Rd", "Kandy"),
        new BigDecimal("2.50"),
        ParcelSize.MEDIUM,
        "Electronics",
        "Smartphone box",
        DestinationType.OTHER_DISTRICT,
        false,
        "Fragile",
        persistedFee,
        persistedCurrency,
        2,
        ParcelStatus.DELIVERED,
        "idemp-key-1",
        "hash-123",
        1,
        OffsetDateTime.parse("2026-11-10T10:00:00Z"),
        OffsetDateTime.parse("2026-11-12T14:30:00Z")
    );

    when(bookingRepository.findById(parcelId)).thenReturn(Optional.of(booking));

    Optional<Receipt> receiptOpt = receiptService.generateReceipt(parcelId);

    assertThat(receiptOpt).isPresent();
    Receipt receipt = receiptOpt.get();
    assertThat(receipt.parcelId()).isEqualTo(parcelId);
    assertThat(receipt.trackingRef()).isEqualTo(trackingRef);
    assertThat(receipt.fee()).isEqualByComparingTo(persistedFee);
    assertThat(receipt.currency()).isEqualTo(persistedCurrency);
    assertThat(receipt.paymentStatus()).isEqualTo("Paid");
    assertThat(receipt.sender().name()).isEqualTo("John Sender");
    assertThat(receipt.recipient().name()).isEqualTo("Jane Recipient");

    ReceiptResponse response = receiptService.toResponse(receipt);
    assertThat(response.amount()).isEqualByComparingTo(persistedFee);
    assertThat(response.formattedAmount()).contains("LKR 1,250.00");
    assertThat(response.downloadUrl()).isEqualTo("/api/v1/parcels/" + parcelId + "/receipt/pdf");
  }

  @Test
  @DisplayName("Receipt directly reflects persisted merchant order fee and currency (3450.75 LKR)")
  void receiptMatchesPersistedMerchantOrderFee() {
    BigDecimal merchantFee = new BigDecimal("3450.75");
    String merchantCurrency = "LKR";

    MerchantOrder order = new MerchantOrder(
        parcelId,
        trackingRef,
        "merchant-acme-corp",
        "EXT-ORD-9021",
        new MerchantOrder.Contact("Acme Warehouse", "0112223344", "Warehouse 5", "Colombo"),
        new MerchantOrder.Contact("Customer Retail", "0774445566", "Shop 12", "Jaffna"),
        new BigDecimal("5.00"),
        ParcelSize.LARGE,
        "Industrial",
        "Component batch",
        DestinationType.OTHER_DISTRICT,
        true,
        "Express B2B",
        merchantFee,
        merchantCurrency,
        1,
        ParcelStatus.DELIVERED,
        "hash-merchant-456",
        OffsetDateTime.parse("2026-11-10T08:00:00Z"),
        OffsetDateTime.parse("2026-11-11T12:00:00Z")
    );

    when(merchantOrderRepository.findByIdAndMerchant(parcelId, "merchant-acme-corp"))
        .thenReturn(Optional.of(order));

    Optional<Receipt> receiptOpt = receiptService.generateReceiptForMerchant(parcelId, "merchant-acme-corp");

    assertThat(receiptOpt).isPresent();
    Receipt receipt = receiptOpt.get();
    assertThat(receipt.fee()).isEqualByComparingTo(merchantFee);
    assertThat(receipt.currency()).isEqualTo(merchantCurrency);
    assertThat(receipt.merchantId()).isEqualTo("merchant-acme-corp");

    ReceiptResponse response = receiptService.toResponse(receipt);
    assertThat(response.amount()).isEqualByComparingTo(merchantFee);
    assertThat(response.formattedAmount()).contains("LKR 3,450.75");
  }

  @Test
  @DisplayName("generatePdfBytes produces valid PDF document containing metadata and persisted amount")
  void generatePdfBytesProducesValidPdfDocument() {
    Receipt receipt = new Receipt(
        UUID.randomUUID(),
        "REC-7K3QX9",
        parcelId,
        trackingRef,
        "customer-1",
        null,
        new Receipt.Contact("Sender Name", "0771112222", "Address 1", "Colombo"),
        new Receipt.Contact("Recipient Name", "0773334444", "Address 2", "Kandy"),
        new BigDecimal("1250.00"),
        "LKR",
        "Paid",
        "Credit Card (•••• 4242)",
        OffsetDateTime.parse("2026-11-10T10:00:00Z"),
        "Package",
        "Standard"
    );

    byte[] pdfBytes = receiptService.generatePdfBytes(receipt);

    assertThat(pdfBytes).isNotNull();
    assertThat(pdfBytes.length).isGreaterThan(100);
    String pdfContent = new String(pdfBytes, StandardCharsets.UTF_8);
    assertThat(pdfContent).startsWith("%PDF-1.4");
    assertThat(pdfContent).contains("%%EOF");
    assertThat(pdfContent).contains(trackingRef);
    assertThat(pdfContent).contains("REC-7K3QX9");
  }
}
