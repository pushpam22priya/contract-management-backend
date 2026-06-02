package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Team;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TeamResponse {

    private String id;
    private String name;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TeamResponse(Team team) {
        this.id = team.getId();
        this.name = team.getName();
        this.createdBy = team.getCreatedBy();
        this.createdAt = team.getCreatedAt();
        this.updatedAt = team.getUpdatedAt();
    }

//    public String getId() { return id; }
//    public String getName() { return name; }
//    public String getCreatedBy() { return createdBy; }
//    public LocalDateTime getCreatedAt() { return createdAt; }
//    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
