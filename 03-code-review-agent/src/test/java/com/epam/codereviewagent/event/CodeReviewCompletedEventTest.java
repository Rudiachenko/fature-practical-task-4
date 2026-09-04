package com.epam.codereviewagent.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.codereviewagent.api.model.CodeReviewResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeReviewCompletedEventTest {

  @Test
  void shouldExposeTheGivenResponse_whenConstructedWithANonNullResponse() {
    // Arrange
    CodeReviewResponse response = new CodeReviewResponse("No issues found.", List.of(), false);

    // Act
    CodeReviewCompletedEvent event = new CodeReviewCompletedEvent(response);

    // Assert
    assertThat(event.response()).isSameAs(response);
  }

  @Test
  void shouldThrowNullPointerException_whenConstructedWithANullResponse() {
    // Act & Assert
    assertThatThrownBy(() -> new CodeReviewCompletedEvent(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining("response");
  }
}
