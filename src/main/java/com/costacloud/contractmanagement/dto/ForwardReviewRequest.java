package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ForwardReviewRequest {

    @NotEmpty(message = "At least one reviewer email is required to forward")
    private List<String> additionalReviewerEmails;

    private String message;
}
