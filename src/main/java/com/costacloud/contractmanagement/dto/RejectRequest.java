package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectRequest {

    @NotBlank(message = "Rejection message is required")
    private String message;
}
