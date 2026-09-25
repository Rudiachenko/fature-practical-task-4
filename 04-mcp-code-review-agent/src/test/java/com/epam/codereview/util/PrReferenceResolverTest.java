package com.epam.codereview.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.codereview.exception.PrReferenceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PrReferenceResolverTest {

  private PrReferenceResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new PrReferenceResolver();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "Please review https://github.com/octocat/Hello-World/pull/42",
    "github.com/octocat/Hello-World/pull/7",
    "octocat/Hello-World#123",
    "please check #42",
    "octocat/Hello-World",
    "Can you review PR #77 in this repo?",
    "#42"
  })
  void shouldNotThrow_whenUserInputCarriesAtLeastOnePrShapedSignal(String userInput) {
    assertThatCode(() -> resolver.validatePrReferencePresent(userInput))
      .doesNotThrowAnyException();
  }

  @Test
  void shouldNotThrow_whenUserInputMatchesTheGithubUrlFragmentCaseInsensitively() {
    assertThatCode(() -> resolver.validatePrReferencePresent(
      "GITHUB.COM/octocat/Hello-World/PULL/9"))
      .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "Please review my latest changes",
    "Can you take a look at this?",
    "I would like a general code review"
  })
  void shouldThrowPrReferenceNotFoundException_whenUserInputHasNoPrShapedSignal(String userInput) {
    assertThatThrownBy(() -> resolver.validatePrReferencePresent(userInput))
      .isInstanceOf(PrReferenceNotFoundException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void shouldThrowPrReferenceNotFoundException_whenUserInputIsBlank(String userInput) {
    assertThatThrownBy(() -> resolver.validatePrReferencePresent(userInput))
      .isInstanceOf(PrReferenceNotFoundException.class);
  }
}
