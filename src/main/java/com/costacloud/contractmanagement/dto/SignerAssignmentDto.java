package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SignerAssignmentDto {

    @NotBlank(message = "partyId is required")
    private String partyId;

    @NotBlank(message = "partyLabel is required")
    private String partyLabel;

    @NotNull(message = "type is required")
    private String type;        // "internal" | "external"

    private String email;
    private String name;
    private String userId;

    @Min(value = 1, message = "Signing order must be 1 or greater")
    private int order;
}

