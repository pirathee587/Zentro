package com.zentro.notificationintegration.webhook.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.notificationintegration.messaging.event.MalformedEventException;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import org.springframework.stereotype.Component;

/**
 * Reads a parcel event for webhook delivery. The envelope is validated by the shared {@link ParcelEventParser}
 * (same contract rules as notifications); this class only adds the optional payload.merchantId.
 */
@Component
public class MerchantEventReader {
  static final int MAX_MERCHANT_ID_LENGTH = 255;

  private final ParcelEventParser parser;
  private final ObjectMapper objectMapper;

  public MerchantEventReader(ParcelEventParser parser, ObjectMapper objectMapper) {
    this.parser = parser;
    this.objectMapper = objectMapper;
  }

  /**
   * @throws MalformedEventException when the envelope breaks the contract, or merchantId is present but is not a
   *                                 non-blank string of at most {@value #MAX_MERCHANT_ID_LENGTH} characters
   */
  public MerchantParcelEvent read(String topic, String message) {
    ParcelEvent event = parser.parse(topic, message);
    JsonNode merchantNode;
    try {
      merchantNode = objectMapper.readTree(message).path("payload").get("merchantId");
    } catch (JsonProcessingException ex) {
      throw new MalformedEventException("not valid JSON");
    }
    String merchantId = null;
    if (merchantNode != null && !merchantNode.isNull()) {
      if (!merchantNode.isTextual() || merchantNode.asText().isBlank()
          || merchantNode.asText().length() > MAX_MERCHANT_ID_LENGTH) {
        throw new MalformedEventException("invalid merchantId");
      }
      merchantId = merchantNode.asText();
    }
    return new MerchantParcelEvent(event, merchantId, message);
  }
}
