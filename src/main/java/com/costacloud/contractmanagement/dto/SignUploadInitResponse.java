package com.costacloud.contractmanagement.dto;

public class SignUploadInitResponse {
    private final String uploadId;

    public SignUploadInitResponse(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getUploadId() { return uploadId; }
}
