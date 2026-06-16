package com.costacloud.contractmanagement.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "signature_requests")
@Data
public class SignatureRequest {

    @Id
    private String id;

    @Indexed(unique = true)
    private String token;

    private String contractId;
    private String contractTitle;

    private String signerEmail;
    private String signerName;

    private String createdBy;        // contractor email
    private String createdByName;    // contractor display name (for email From line)

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    // pending | signed | expired
    private String status;
    private LocalDateTime signedAt;

    private List<String> assignedParty;
    private List<String> assignedPartyLabel;

    private int order;

    // Contract state snapshot — what the signer sees when they open the link
    private List<Map<String, Object>> formFields;
    private String xfdfData;
    private Map<String, String> fieldValues;

    // Version of the contract when this request was created — used for 409 check on submit
    private int contractVersion;
}
