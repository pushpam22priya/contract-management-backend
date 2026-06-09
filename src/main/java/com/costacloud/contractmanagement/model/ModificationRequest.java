package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ModificationRequest {
    private String requestedBy;     // email of who triggered this entry
    private String role;            // "reviewer" | "approver" | "contractor"
    private String message;
    private LocalDateTime requestedAt;
}
