package com.costacloud.contractmanagement.dto;

import java.util.List;

public class ChunkCompleteRequest {

    private String uploadId;
    private List<Part> parts;

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
}
