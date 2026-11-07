package com.zentro.parcelpricing.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/** Retain decimal precision while refusing coercion, exponent syntax and excessive input. */
public final class StrictDecimalDeserializer extends JsonDeserializer<BigDecimal> {
  @Override
  public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    String text = parser.getText();
    if (!(parser.hasToken(JsonToken.VALUE_NUMBER_INT) || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT))
        || text.length() > 32 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
      throw com.fasterxml.jackson.databind.exc.InvalidFormatException.from(parser,
          "Enter a decimal JSON number without exponent notation.", null, BigDecimal.class);
    }
    return parser.getDecimalValue();
  }
}
