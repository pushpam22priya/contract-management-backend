package com.costacloud.contractmanagement.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "contracts")
public class Contract {

    @Id
    private String id;

    private String contractName;
    private List<String> parties;
    private LocalDate startDate;
    private LocalDate endDate;
    private ContractStatus status;
    private String fileKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum ContractStatus {
        DRAFT, ACTIVE, EXPIRED, TERMINATED
    }

    public Contract() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // getters and setters for all fields...
}
