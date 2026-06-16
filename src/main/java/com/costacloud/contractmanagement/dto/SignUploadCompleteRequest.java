package com.costacloud.contractmanagement.dto;

import java.util.List;
import java.util.Map;

public class SignUploadCompleteRequest {

    private String uploadId;
    private List<Part> parts;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;
    private boolean autoSave;

    public static class Part {
        private int partNumber;
        private String eTag;

        public int getPartNumber() { return partNumber; }
        public void setPartNumber(int partNumber) { this.partNumber = partNumber; }
        public String getETag() { return eTag; }
        public void setETag(String eTag) { this.eTag = eTag; }
    }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public List<Part> getParts() { return parts; }
    public void setParts(List<Part> parts) { this.parts = parts; }
    public Map<String, String> getFieldValues() { return fieldValues; }
    public void setFieldValues(Map<String, String> fieldValues) { this.fieldValues = fieldValues; }
    public List<Map<String, Object>> getFormFields() { return formFields; }
    public void setFormFields(List<Map<String, Object>> formFields) { this.formFields = formFields; }
    public boolean isAutoSave() { return autoSave; }
    public void setAutoSave(boolean autoSave) { this.autoSave = autoSave; }
}
