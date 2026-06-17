package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExternalSigner {

    private String email;
    private String name;
    private String partyId;
    private String partyLabel;

    // Unique per signer — no two signers in any submission share an order
    private int order;

    // Signing round this signer belongs to (1 = first submission, 2 = re-share after all-completed, ...)
    private int signingRound;

    // Unique URL-safe token — forms the public signing link /sign/{token}
    private String token;

    // pending → unlocked → viewed → completed
    private String status;

    private LocalDateTime sentAt;
    private LocalDateTime unlockedAt;
    private LocalDateTime viewedAt;
    private LocalDateTime completedAt;
}
