package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PartyCompletion {

    private String partyId;
    private String partyLabel;
    private int order;

    // "internal" | "external"
    private String assigneeType;
    private String assigneeEmail;
    private String assigneeName;

    // pending → unlocked → completed
    private String status;

    private String completedBy;
    private LocalDateTime completedAt;
}