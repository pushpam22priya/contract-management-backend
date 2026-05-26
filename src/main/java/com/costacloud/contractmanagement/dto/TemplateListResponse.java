package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Template;

import java.time.LocalDateTime;
import java.util.List;

public class TemplateListResponse {

    private String id;
    private String name;
    private String description;
    private String category;
    private String fileName;
    private String fileUrl;
    private String uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int timesUsed;
    private boolean hasFormFields;
    private boolean fileUploaded;
    private List<Template.Party> parties;

    public TemplateListResponse(Template t) {
        this.id = t.getId();
        this.name = t.getName();
        this.description = t.getDescription();
        this.category = t.getCategory();
        this.fileName = t.getFileName();
        this.fileUrl = "/templates/" + t.getId() + "/file";
        this.uploadedBy = t.getUploadedBy();
        this.createdAt = t.getCreatedAt();
        this.updatedAt = t.getUpdatedAt();
        this.timesUsed = t.getTimesUsed();
        this.hasFormFields = t.isHasFormFields();
        this.fileUploaded = t.isFileUploaded();
        this.parties = t.getParties();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getFileName() { return fileName; }
    public String getFileUrl() { return fileUrl; }
    public String getUploadedBy() { return uploadedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getTimesUsed() { return timesUsed; }
    public boolean isHasFormFields() { return hasFormFields; }
    public boolean isFileUploaded() { return fileUploaded; }
    public List<Template.Party> getParties() { return parties; }
}
