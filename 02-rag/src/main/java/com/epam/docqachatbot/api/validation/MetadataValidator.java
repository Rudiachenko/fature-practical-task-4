package com.epam.docqachatbot.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

public class MetadataValidator implements ConstraintValidator<ValidMetadata, Map<String, Object>> {

  private static final Set<Class<?>> ALLOWED_NUMBER_TYPES = Set.of(
    Byte.class,
    Short.class,
    Integer.class,
    Long.class,
    Float.class,
    Double.class,
    BigInteger.class,
    BigDecimal.class
  );

  private int maxStringLength;

  @Override
  public void initialize(ValidMetadata annotation) {
    maxStringLength = annotation.maxStringLength();
  }

  @Override
  public boolean isValid(Map<String, Object> metadata,
                         ConstraintValidatorContext context) {
    if (metadata == null) {
      return true;
    }
    return metadata.values().stream().allMatch(this::isAllowedScalar);
  }

  private boolean isAllowedScalar(Object value) {
    if (value instanceof String stringValue) {
      return stringValue.length() <= maxStringLength;
    }
    if (value instanceof Boolean) {
      return true;
    }
    if (value == null || !ALLOWED_NUMBER_TYPES.contains(value.getClass())) {
      return false;
    }
    if (value instanceof Double doubleValue) {
      return Double.isFinite(doubleValue);
    }
    if (value instanceof Float floatValue) {
      return Float.isFinite(floatValue);
    }
    return true;
  }
}
