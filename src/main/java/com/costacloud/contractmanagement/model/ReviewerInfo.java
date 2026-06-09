package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReviewerInfo {
    private String email;
    private String status;              // pending | reviewed | forwarded | rejected
    private LocalDateTime reviewedAt;
    private LocalDateTime rejectedAt;
    private String comments;
    private String submissionMessage;   // message sent to reviewer at assignment time
    private LocalDateTime sentAt;
    private String sentBy;              // email of contractor who assigned this reviewer
}
