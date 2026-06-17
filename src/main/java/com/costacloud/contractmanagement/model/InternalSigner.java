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

    // Unique per signer within a round
    private int order;

    // Signing round this signer belongs to (1 = first submission, 2 = re-share after all-completed, ...)
    private int signingRound;

    // pending → unlocked → completed  (no "viewed" — they see inbox card directly)
    private String status;

    private LocalDateTime assignedAt;
    private LocalDateTime unlockedAt;
    private LocalDateTime completedAt;
}
