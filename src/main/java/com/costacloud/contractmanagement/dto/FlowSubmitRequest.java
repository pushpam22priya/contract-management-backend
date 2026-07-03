package com.costacloud.contractmanagement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;

@Data
public class FlowSubmitRequest {

    @NotEmpty(message = "At least one participant is required")
    @Valid
    private List<ParticipantAssignment> participants;

    private boolean externalSigningIncluded;

    // Required when externalSigningIncluded = true
    private List<ExternalSignerAssignment> externalSigners;

    // Display name used in signature emails (e.g. "Priya from CostaCloud")
    private String senderName;
}
