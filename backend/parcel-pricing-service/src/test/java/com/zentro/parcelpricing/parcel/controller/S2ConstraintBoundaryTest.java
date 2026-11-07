package com.zentro.parcelpricing.parcel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.parcel.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.pricing.rules.PricingCalculationEngine;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S2ConstraintBoundaryTest {
  private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

  @ParameterizedTest
  @ValueSource(ints = {0, 7, 8, 9, 254, 255, 256, 100000})
  void addressCodePointBoundariesForBothContactsAndEndpoints(int length) {
    for (String unit : new String[]{"x", "😀", "א"}) {
      var contact = new CreateParcelBookingRequest.Contact("Synthetic", "0770000000", "  " + unit.repeat(length) + "  ", "Synthetic");
      var parcel = new CreateParcelBookingRequest.ParcelDetails(BigDecimal.ONE, "Test", ParcelSize.SMALL, null);
      var booking = new CreateParcelBookingRequest(contact, contact, parcel, null, false, null);
      var merchant = new CreateMerchantOrderRequest("SYNTHETIC-124", contact, contact, parcel, null, false, null);
      boolean expected = length >= 8 && length <= 255;
      assertThat(VALIDATOR.validate(booking).isEmpty()).isEqualTo(expected);
      assertThat(VALIDATOR.validate(merchant).isEmpty()).isEqualTo(expected);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.09", "0.1", "0.11", "19.99", "20", "20.01", "1.1234", "1.12345"})
  void sharedWeightBoundary(String raw) {
    BigDecimal weight = new BigDecimal(raw);
    var parcel = new CreateParcelBookingRequest.ParcelDetails(weight, "Test", ParcelSize.SMALL, null);
    var quote = new FeeEstimateRequest(null, null, null, null, weight, null, false, null, null, null, null, null, null, null);
    assertThat(VALIDATOR.validate(parcel).isEmpty()).isEqualTo(VALIDATOR.validate(quote).isEmpty());
    assertThat(VALIDATOR.validate(parcel).isEmpty()).isEqualTo(weight.compareTo(new BigDecimal("0.1")) >= 0
        && weight.compareTo(new BigDecimal("20")) <= 0 && weight.scale() <= 4);
  }

  @Test
  void sizeIsFallbackRatherThanAWeightTier() {
    var request = new FeeEstimateRequest(null, null, null, ParcelSize.SMALL, new BigDecimal("15"), null, false, null, null, null, null, null, null, null);
    assertThat(VALIDATOR.validate(request)).isEmpty();
    var quote = new PricingCalculationEngine().calculate(request, PricingRule.defaultStandard());
    assertThat(quote.breakdown().weightFee()).isEqualByComparingTo("1680.00");
    assertThat(quote.fee()).isEqualByComparingTo("2230.00");
  }

  @Test
  void allTextLengthEdgesAreSharedByBookingAndMerchantRequests() {
    for (String field : new String[]{"name", "city", "category", "description", "notes"}) {
      int max = switch (field) { case "name", "city" -> 100; case "category" -> 40; case "description" -> 255; default -> 500; };
      for (int length : new int[]{0, 1, 2, max - 1, max, max + 1, 100000}) {
        String text = "😀".repeat(length);
        var contact = new CreateParcelBookingRequest.Contact(field.equals("name") ? text : "Synthetic", "0770000000", "Synthetic Road", field.equals("city") ? text : null);
        var parcel = new CreateParcelBookingRequest.ParcelDetails(BigDecimal.ONE, field.equals("category") ? text : "Test", ParcelSize.SMALL, field.equals("description") ? text : null);
        var booking = new CreateParcelBookingRequest(contact, contact, parcel, null, false, field.equals("notes") ? text : null);
        var merchant = new CreateMerchantOrderRequest("SYNTHETIC", contact, contact, parcel, null, false, booking.notes());
        boolean valid = length <= max && (!(field.equals("name") || field.equals("category")) || length > 0);
        assertThat(VALIDATOR.validate(booking).isEmpty()).as(field + " length " + length).isEqualTo(valid);
        assertThat(VALIDATOR.validate(merchant).isEmpty()).isEqualTo(valid);
      }
    }
  }

  @Test
  void estimateDimensionsAndDistanceHaveBoundedPrecision() {
    for (String field : new String[]{"lengthCm", "widthCm", "heightCm", "distanceKm"}) {
      String min = field.equals("distanceKm") ? "0" : "0.01";
      String max = field.equals("distanceKm") ? "2000" : "500";
      BigDecimal lower = new BigDecimal(min), upper = new BigDecimal(max), step = new BigDecimal("0.01");
      for (BigDecimal value : new BigDecimal[]{lower.subtract(step), lower, lower.add(step), upper.subtract(step), upper, upper.add(step), new BigDecimal("1.12345")}) {
        var quote = new FeeEstimateRequest(null, null, null, ParcelSize.SMALL, BigDecimal.ONE, null, false,
            field.equals("distanceKm") ? value : null, field.equals("lengthCm") ? value : null,
            field.equals("widthCm") ? value : null, field.equals("heightCm") ? value : null, null, null, null);
        assertThat(VALIDATOR.validate(quote).isEmpty()).as(field + " " + value).isEqualTo(value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0 && value.scale() <= 4);
      }
    }
  }

  @Test
  void printableContentIsNotSanitizedAndCombiningMarksCountSeparately() {
    for (String address : new String[]{"<b>Synthetic</b>", "SELECT synthetic; --", "استخدام عنوان", "a\u0301".repeat(4)}) {
      var contact = new CreateParcelBookingRequest.Contact("Synthetic", "0770000000", address, null);
      assertThat(VALIDATOR.validate(contact)).isEmpty();
      assertThat(contact.address()).isEqualTo(address);
    }
    assertThat(VALIDATOR.validate(new CreateParcelBookingRequest.Contact("Synthetic", "0770000000", null, null))).isNotEmpty();
  }
}
