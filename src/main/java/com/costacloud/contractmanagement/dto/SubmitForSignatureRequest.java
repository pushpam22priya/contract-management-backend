package com.costacloud.contractmanagement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class SubmitForSignatureRequest {

    @NotEmpty(message = "At least one signer assignment is required")
    @Valid
    private List<SignerAssignmentDto> assignments;

    private String senderName;   // Contractor display name — used in email From field
}

