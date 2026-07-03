package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.ParticipantRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ParticipantAssignment {

    @NotBlank(message = "Participant email is required")
    @Email(message = "Invalid email format for participant")
    private String email;

    private String name;

    @NotNull(message = "Participant role is required")
    private ParticipantRole role;

    @Positive(message = "Order must be a positive integer")
    private int order;
}
