package com.costacloud.contractmanagement.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FlowCompleteRequest {

    private String comments;
    private String uploadId;
    private List<Part> parts;
    private List<Map<String, Object>> formFields;
    private Map<String, String> fieldValues;
    private String xfdfData;

    @Data
    public static class Part {
        private int partNumber;
        private String etag;
    }
}
