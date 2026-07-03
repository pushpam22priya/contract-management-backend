package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ExternalSignerAssignment {

    @NotBlank(message = "External signer email is required")
    @Email(message = "Invalid email format for external signer")
    private String email;

    private String name;

    @NotBlank(message = "partyId is required")
    private String partyId;

    @NotBlank(message = "partyLabel is required")
    private String partyLabel;

    @Positive(message = "Signing order must be a positive integer")
    private int order;
}
