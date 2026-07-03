package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FlowRejectRequest {

    @NotBlank(message = "Rejection reason is required")
    private String message;
}
