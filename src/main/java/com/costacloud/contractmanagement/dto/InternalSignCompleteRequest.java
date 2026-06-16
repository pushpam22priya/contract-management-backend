package com.costacloud.contractmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class InternalSignCompleteRequest {

    @NotBlank(message = "Signer email is required")
    private String signerEmail;

    private String uploadId;
    private List<Part> parts;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;

    @Data
    public static class Part {
        private int partNumber;
        private String eTag;
    }
}
