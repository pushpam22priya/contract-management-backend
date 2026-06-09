package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ApproverInfo {
    private String email;
    private String status;              // pending | approved | rejected
    private LocalDateTime approvedAt;
    private LocalDateTime rejectedAt;   // dedicated field — not reusing approvedAt
    private String comments;
    private String submissionMessage;
    private LocalDateTime sentAt;
    private String sentBy;
}
