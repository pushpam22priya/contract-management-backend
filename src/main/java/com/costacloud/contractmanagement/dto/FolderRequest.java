package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FolderRequest {

    @NotBlank(message = "Folder name is required")
    @Size(max = 50, message = "Folder name must be 50 characters or less")
    private String name;
}
