package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Folder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FolderResponse {

    private String id;
    private String name;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public FolderResponse(Folder folder) {
        this.id = folder.getId();
        this.name = folder.getName();
        this.createdBy = folder.getCreatedBy();
        this.createdAt = folder.getCreatedAt();
        this.updatedAt = folder.getUpdatedAt();
    }
}
