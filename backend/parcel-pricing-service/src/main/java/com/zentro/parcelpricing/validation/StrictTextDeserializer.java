package com.zentro.parcelpricing.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;

public final class StrictTextDeserializer extends JsonDeserializer<String> {
  @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
      throw MismatchedInputException.from(parser, String.class, "Enter a JSON string.");
    }
    return parser.getText();
  }
}
