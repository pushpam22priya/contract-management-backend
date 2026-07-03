package com.costacloud.contractmanagement.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkflowParticipant {
    private String email;
    private String name;
    private ParticipantRole role;
    private int order;
    private String status; // pending | unlocked | in_progress | completed | rejected
    private String sentBy;
    private LocalDateTime sentAt;
    private LocalDateTime unlockedAt;
    private LocalDateTime completedAt;
    private LocalDateTime rejectedAt;
    private String comments;
}
