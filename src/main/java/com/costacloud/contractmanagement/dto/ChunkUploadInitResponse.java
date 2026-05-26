package com.costacloud.contractmanagement.dto;

public class ChunkUploadInitResponse {

    private String uploadId;
    private String templateId;

    public ChunkUploadInitResponse(String uploadId, String templateId) {
        this.uploadId = uploadId;
        this.templateId = templateId;
    }

    public String getUploadId() { return uploadId; }
    public String getTemplateId() { return templateId; }
}
