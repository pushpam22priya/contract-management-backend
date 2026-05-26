package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Template;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class TemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 50, message = "Template name must not exceed 50 characters")
    private String name;

    private String description;

    @NotBlank(message = "Category is required")
    private String category;

    private String fileName;
    private String xfdfData;
    private List<Template.FormField> formFields;
    private List<Template.Party> parties;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getXfdfData() { return xfdfData; }
    public void setXfdfData(String xfdfData) { this.xfdfData = xfdfData; }
    public List<Template.FormField> getFormFields() { return formFields; }
    public void setFormFields(List<Template.FormField> formFields) { this.formFields = formFields; }
    public List<Template.Party> getParties() { return parties; }
    public void setParties(List<Template.Party> parties) { this.parties = parties; }
}
