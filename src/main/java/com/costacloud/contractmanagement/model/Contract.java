package com.costacloud.contractmanagement.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "contracts")
@CompoundIndexes({
    @CompoundIndex(name = "idx_createdBy_teamId_status", def = "{'createdBy': 1, 'teamId': 1, 'status': 1}"),
    @CompoundIndex(name = "idx_title_createdBy",         def = "{'title': 1, 'createdBy': 1}"),
    @CompoundIndex(name = "idx_fileUploaded_uploadAt",   def = "{'fileUploaded': 1, 'uploadInitiatedAt': 1}")
})
@Data
public class Contract {

    @Id
    private String id;

    private String title;
    private String client;
    private String description;
    private String value;
    private String category;
    private ContractStatus status;

    private LocalDate startDate;
    private LocalDate endDate;

    private String templateId;
    private String templateName;
    private String templateFileName;

    private String xfdfData;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;
    private boolean hasFormFields;
    private List<Party> parties;

    private boolean fileUploaded;
    private String uploadId;
    private LocalDateTime uploadInitiatedAt;

    private String teamId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
