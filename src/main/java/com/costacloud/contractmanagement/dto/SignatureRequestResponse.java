package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Party;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class SignatureRequestResponse {

    private String token;
    private String contractId;
    private String contractTitle;
    private String signerEmail;
    private String signerName;
    private String status;
    private LocalDateTime expiresAt;

    private List<String> assignedParty;
    private List<String> assignedPartyLabel;

    private List<Map<String, Object>> formFields;
    private String xfdfData;
    private Map<String, String> fieldValues;

    private List<Party> parties;
    private int contractVersion;
}

