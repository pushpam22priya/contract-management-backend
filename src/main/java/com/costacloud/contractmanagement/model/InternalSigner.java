package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class InternalSigner {

    private String userId;
    private String email;
    private String name;
    private String partyId;
    private String partyLabel;

    // Unique per signer
    private int order;

    // pending → unlocked → completed  (no "viewed" — they see inbox card directly)
    private String status;

    private LocalDateTime assignedAt;
    private LocalDateTime unlockedAt;
    private LocalDateTime completedAt;
}
