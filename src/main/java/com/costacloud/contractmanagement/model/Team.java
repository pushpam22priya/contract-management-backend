package com.costacloud.contractmanagement.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "teams")
@Data
public class Team {

    @Id
    private String id;

    private String name;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

//    public String getId() { return id; }
//    public void setId(String id) { this.id = id; }
//
//    public String getName() { return name; }
//    public void setName(String name) { this.name = name; }
//
//    public String getCreatedBy() { return createdBy; }
//    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
//
//    public LocalDateTime getCreatedAt() { return createdAt; }
//    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
//
//    public LocalDateTime getUpdatedAt() { return updatedAt; }
//    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
