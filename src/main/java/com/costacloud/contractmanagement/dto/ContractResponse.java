package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.Party;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ContractResponse extends ContractListResponse {

    private String templateFileName;
    private String xfdfData;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;
    private List<Party> parties;

    public ContractResponse(Contract c) {
        super(c);
        this.templateFileName = c.getTemplateFileName();
        this.xfdfData = c.getXfdfData();
        this.fieldValues = c.getFieldValues();
        this.formFields = c.getFormFields();
        this.parties = c.getParties();
    }
}
