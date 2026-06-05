package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Party;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class ContractRequest {

    @NotBlank(message = "Contract title is required")
    @Size(max = 50, message = "Contract title must be 50 characters or less")
    private String title;

    @NotBlank(message = "Client name is required")
    @Size(max = 50, message = "Client name must be 50 characters or less")
    private String client;

    @Size(max = 500, message = "Description must be 500 characters or less")
    private String description;

    @Size(max = 50, message = "Category must be 50 characters or less")
    private String category;

    @NotBlank(message = "Template ID is required")
    private String templateId;

    private String templateName;
    private String templateFileName;

    private LocalDate startDate;
    private LocalDate endDate;

    private String xfdfData;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;
    private boolean hasFormFields;
    private List<Party> parties;

    private String teamId;
}
