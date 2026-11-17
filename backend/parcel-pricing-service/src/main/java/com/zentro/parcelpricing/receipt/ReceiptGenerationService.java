package com.zentro.parcelpricing.receipt;

import com.zentro.parcelpricing.parcel.entity.MerchantOrder;
import com.zentro.parcelpricing.parcel.entity.ParcelBooking;
import com.zentro.parcelpricing.parcel.repository.MerchantOrderRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Generates official delivery billing receipts.
 * Strictly uses persisted booking price and currency without repricing or invoking PricingService.
 */
@Service
public class ReceiptGenerationService {

  private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
  private final ParcelBookingRepository bookingRepository;
  private final MerchantOrderRepository merchantOrderRepository;

  public ReceiptGenerationService(
      ParcelBookingRepository bookingRepository,
      MerchantOrderRepository merchantOrderRepository) {
    this.bookingRepository = bookingRepository;
    this.merchantOrderRepository = merchantOrderRepository;
  }

  /**
   * Generates a Receipt model using strictly persisted booking price and currency without repricing.
   */
  public Optional<Receipt> generateReceipt(UUID parcelId) {
    if (parcelId == null) {
      return Optional.empty();
    }
    Optional<ParcelBooking> bookingOpt = bookingRepository.findById(parcelId);
    if (bookingOpt.isPresent()) {
      return Optional.of(fromBooking(bookingOpt.get()));
    }
    return Optional.empty();
  }

  /**
   * Generates a receipt scoped to the customer owner. Returns empty if missing or owned by another.
   */
  public Optional<Receipt> generateReceiptForCustomer(UUID parcelId, String customerSubject) {
    if (parcelId == null || customerSubject == null || customerSubject.isBlank()) {
      return Optional.empty();
    }
    return bookingRepository.findByIdAndOwner(parcelId, customerSubject)
        .map(this::fromBooking);
  }

  /**
   * Generates a receipt scoped to the merchant owner. Returns empty if missing or owned by another.
   */
  public Optional<Receipt> generateReceiptForMerchant(UUID parcelId, String merchantId) {
    if (parcelId == null || merchantId == null || merchantId.isBlank()) {
      return Optional.empty();
    }
    return merchantOrderRepository.findByIdAndMerchant(parcelId, merchantId)
        .map(this::fromMerchantOrder);
  }

  public java.util.List<ReceiptResponse> customerReceipts(String subject) {
    return bookingRepository.findCustomerPage(subject, java.util.List.of("DELIVERED"), null, 0, 50)
        .stream().map(this::fromBooking).map(this::toResponse).toList();
  }

  public Receipt fromBooking(ParcelBooking booking) {
    String cleanRef = booking.trackingRef().replace("-", "");
    String receiptNum = "REC-" + (cleanRef.length() >= 6 ? cleanRef.substring(cleanRef.length() - 6) : cleanRef);
    UUID receiptId = UUID.nameUUIDFromBytes(("receipt:" + booking.id()).getBytes(StandardCharsets.UTF_8));

    return new Receipt(
        receiptId,
        receiptNum,
        booking.id(),
        booking.trackingRef(),
        booking.customerSubject(),
        null,
        new Receipt.Contact(
            booking.sender() != null ? booking.sender().name() : "",
            booking.sender() != null ? booking.sender().phone() : "",
            booking.sender() != null ? booking.sender().address() : "",
            booking.sender() != null ? booking.sender().city() : ""),
        new Receipt.Contact(
            booking.recipient() != null ? booking.recipient().name() : "",
            booking.recipient() != null ? booking.recipient().phone() : "",
            booking.recipient() != null ? booking.recipient().address() : "",
            booking.recipient() != null ? booking.recipient().city() : ""),
        booking.fee(), // Strictly persisted fee: NO REPRICING
        booking.currency(),
        "Unknown",
        "Not recorded",
        booking.createdAt(),
        booking.description(),
        booking.category()
    );
  }

  public Receipt fromMerchantOrder(MerchantOrder order) {
    String cleanRef = order.trackingRef().replace("-", "");
    String receiptNum = "REC-M-" + (cleanRef.length() >= 6 ? cleanRef.substring(cleanRef.length() - 6) : cleanRef);
    UUID receiptId = UUID.nameUUIDFromBytes(("receipt:" + order.id()).getBytes(StandardCharsets.UTF_8));

    return new Receipt(
        receiptId,
        receiptNum,
        order.id(),
        order.trackingRef(),
        order.merchantId(),
        order.merchantId(),
        new Receipt.Contact(
            order.sender() != null ? order.sender().name() : "",
            order.sender() != null ? order.sender().phone() : "",
            order.sender() != null ? order.sender().address() : "",
            order.sender() != null ? order.sender().city() : ""),
        new Receipt.Contact(
            order.recipient() != null ? order.recipient().name() : "",
            order.recipient() != null ? order.recipient().phone() : "",
            order.recipient() != null ? order.recipient().address() : "",
            order.recipient() != null ? order.recipient().city() : ""),
        order.fee(), // Strictly persisted fee: NO REPRICING
        order.currency(),
        "Unknown",
        "Not recorded",
        order.createdAt(),
        order.description(),
        order.category()
    );
  }

  public ReceiptResponse toResponse(Receipt receipt) {
    String formattedAmount = formatMoney(receipt.fee(), receipt.currency());
    String dateStr = receipt.issuedAt() != null ? receipt.issuedAt().toInstant().toString() : "";
    String formattedDate = receipt.issuedAt() != null ? DISPLAY_DATE.format(receipt.issuedAt()) : "";

    return new ReceiptResponse(
        receipt.id(),
        receipt.receiptNumber(),
        receipt.parcelId(),
        receipt.trackingRef(),
        receipt.fee(),
        formattedAmount,
        receipt.currency(),
        receipt.paymentStatus(),
        receipt.paymentMethod(),
        "/api/v1/parcels/" + receipt.parcelId() + "/receipt/pdf",
        dateStr,
        formattedDate,
        receipt.sender() != null ? receipt.sender().name() : "",
        receipt.recipient() != null ? receipt.recipient().name() : ""
    );
  }

  public byte[] generatePdfBytes(Receipt receipt) {
    StringBuilder text = new StringBuilder();
    text.append("====================================================\n");
    text.append("                 ZENTRO DELIVERY RECEIPT            \n");
    text.append("====================================================\n");
    text.append("Receipt No:   ").append(receipt.receiptNumber()).append("\n");
    text.append("Tracking Ref: ").append(receipt.trackingRef()).append("\n");
    text.append("Date:         ").append(receipt.issuedAt() != null ? DISPLAY_DATE.format(receipt.issuedAt()) : "").append("\n");
    text.append("Payment:      ").append(receipt.paymentStatus()).append(" via ").append(receipt.paymentMethod()).append("\n");
    text.append("----------------------------------------------------\n");
    text.append("Sender:       ").append(receipt.sender().name()).append(" (").append(receipt.sender().city()).append(")\n");
    text.append("Recipient:    ").append(receipt.recipient().name()).append(" (").append(receipt.recipient().city()).append(")\n");
    text.append("----------------------------------------------------\n");
    text.append("Total Amount: ").append(formatMoney(receipt.fee(), receipt.currency())).append("\n");
    text.append("====================================================\n");

    return generateMinimalPdf(text.toString());
  }

  private static byte[] generateMinimalPdf(String content) {
    String escaped = content.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        .replace("\n", ") Tj T* (");
    String stream = "BT /F1 10 Tf 50 750 Td (" + escaped + ") Tj ET";
    int streamLen = stream.getBytes(StandardCharsets.UTF_8).length;

    String pdf = "%PDF-1.4\n"
        + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
        + "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
        + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n"
        + "4 0 obj << /Length " + streamLen + " >> stream\n" + stream + "\nendstream\nendobj\n"
        + "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Courier >> endobj\n"
        + "xref\n0 6\n"
        + "0000000000 65535 f \n"
        + "0000000009 00000 n \n"
        + "0000000058 00000 n \n"
        + "0000000115 00000 n \n"
        + "0000000244 00000 n \n"
        + "0000000320 00000 n \n"
        + "trailer << /Size 6 /Root 1 0 R >>\n"
        + "startxref\n395\n%%EOF\n";

    return pdf.getBytes(StandardCharsets.UTF_8);
  }

  private String formatMoney(BigDecimal amount, String currency) {
    if (amount == null) return (currency != null ? currency : "LKR") + " 0.00";
    String curr = currency != null ? currency : "LKR";
    return curr + " " + String.format(Locale.ENGLISH, "%,.2f", amount);
  }
}
