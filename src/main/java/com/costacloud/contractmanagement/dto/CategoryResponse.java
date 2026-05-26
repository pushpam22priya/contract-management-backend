package com.costacloud.contractmanagement.dto;

import java.time.LocalDateTime;

public class CategoryResponse {

    private String id;
    private String name;
    private String createdBy;
    private LocalDateTime createdAt;

    public CategoryResponse(String id, String name, String createdBy, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
