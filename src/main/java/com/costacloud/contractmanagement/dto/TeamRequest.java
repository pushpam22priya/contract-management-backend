package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TeamRequest {

    @NotBlank(message = "Team name is required")
    @Size(max = 50, message = "Team name must be 50 characters or less")
    private String name;

//    public String getName() { return name; }
//    public void setName(String name) { this.name = name; }
}
