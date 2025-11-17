package com.epam.codereview.api.model;

import jakarta.validation.constraints.NotBlank;

public record UserRequest(

  @NotBlank String userInput) {

}



