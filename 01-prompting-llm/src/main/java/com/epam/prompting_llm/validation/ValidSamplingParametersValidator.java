package com.epam.prompting_llm.validation;

import com.epam.prompting_llm.api.model.PromptRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implements the cross-field temperature and topP exclusivity check. */
public class ValidSamplingParametersValidator
  implements ConstraintValidator<ValidSamplingParameters, PromptRequest> {

  @Override
  public boolean isValid(PromptRequest request, ConstraintValidatorContext context) {
    return request == null || request.temperature() == null || request.topP() == null;
  }
}
