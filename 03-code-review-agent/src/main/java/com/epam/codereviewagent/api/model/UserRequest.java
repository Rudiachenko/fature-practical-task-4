package com.epam.codereviewagent.api.model;

import jakarta.validation.constraints.NotBlank;

public record UserRequest(

  @NotBlank String userInput) {

}



