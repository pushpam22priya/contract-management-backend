package com.costacloud.contractmanagement.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "templates")
public class Template {

    @Id
    private String id;

    @Indexed(unique = true)
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
    private String uploadId;
    private LocalDateTime uploadInitiatedAt;
    private String xfdfData;
    private List<FormField> formFields;
    private List<Party> parties;

    public static class FormField {
        private String name;
        private String type;
        private String label;
        private double x;
        private double y;
        private double width;
        private double height;
        private int pageNumber;
        private String annotationId;
        private boolean required;
        private boolean readOnly;
        private boolean multiline;
        private String defaultValue;
        private String placeholder;
        private List<String> options;
        private String assignedParty;
        private String partyLabel;
        private String profileKey;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }
        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        public String getAnnotationId() { return annotationId; }
        public void setAnnotationId(String annotationId) { this.annotationId = annotationId; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public boolean isReadOnly() { return readOnly; }
        public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
        public boolean isMultiline() { return multiline; }
        public void setMultiline(boolean multiline) { this.multiline = multiline; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
        public String getPlaceholder() { return placeholder; }
        public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
        public List<String> getOptions() { return options; }
        public void setOptions(List<String> options) { this.options = options; }
        public String getAssignedParty() { return assignedParty; }
        public void setAssignedParty(String assignedParty) { this.assignedParty = assignedParty; }
        public String getPartyLabel() { return partyLabel; }
        public void setPartyLabel(String partyLabel) { this.partyLabel = partyLabel; }
        public String getProfileKey() { return profileKey; }
        public void setProfileKey(String profileKey) { this.profileKey = profileKey; }
    }

    public static class Party {
        private String id;
        private String label;
        private String color;
        private int order;
        private PartyType type;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }

        public PartyType getType() {
            return type;
        }

        public void setType(PartyType type) {
            this.type = type;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public int getTimesUsed() { return timesUsed; }
    public void setTimesUsed(int timesUsed) { this.timesUsed = timesUsed; }
    public boolean isHasFormFields() { return hasFormFields; }
    public void setHasFormFields(boolean hasFormFields) { this.hasFormFields = hasFormFields; }
    public boolean isFileUploaded() { return fileUploaded; }
    public void setFileUploaded(boolean fileUploaded) { this.fileUploaded = fileUploaded; }
    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public LocalDateTime getUploadInitiatedAt() { return uploadInitiatedAt; }
    public void setUploadInitiatedAt(LocalDateTime uploadInitiatedAt) { this.uploadInitiatedAt = uploadInitiatedAt; }
    public String getXfdfData() { return xfdfData; }
    public void setXfdfData(String xfdfData) { this.xfdfData = xfdfData; }
    public List<FormField> getFormFields() { return formFields; }
    public void setFormFields(List<FormField> formFields) { this.formFields = formFields; }
    public List<Party> getParties() { return parties; }
    public void setParties(List<Party> parties) { this.parties = parties; }
}
