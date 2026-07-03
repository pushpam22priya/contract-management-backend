package com.costacloud.contractmanagement.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FlowFieldEditRequest {
    private List<Map<String, Object>> formFields;
    private Map<String, String> fieldValues;
    private String xfdfData;
}
