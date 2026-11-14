package com.zentro.parcelpricing.receipt;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public/client view of an official delivery receipt.
 */
public record ReceiptResponse(
    UUID id,
    String receiptNumber,
    UUID parcelId,
    String trackingRef,
    BigDecimal amount,
    String formattedAmount,
    String currency,
    String paymentStatus,
    String paymentMethod,
    String downloadUrl,
    String date,
    String formattedDate,
    String senderName,
    String recipientName
) {}
