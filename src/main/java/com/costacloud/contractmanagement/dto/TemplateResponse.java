package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Template;

import java.time.LocalDateTime;
import java.util.List;

public class TemplateResponse extends TemplateListResponse {

    private String xfdfData;
    private List<Template.FormField> formFields;

    public TemplateResponse(Template t) {
        super(t);
        this.xfdfData = t.getXfdfData();
        this.formFields = t.getFormFields();
    }

    public String getXfdfData() { return xfdfData; }
    public List<Template.FormField> getFormFields() { return formFields; }
}
