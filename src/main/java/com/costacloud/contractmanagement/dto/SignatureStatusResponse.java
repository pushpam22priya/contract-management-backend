package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.ExternalSigner;
import com.costacloud.contractmanagement.model.InternalSigner;
import com.costacloud.contractmanagement.model.PartyCompletion;
import lombok.Data;

import java.util.List;

@Data
public class SignatureStatusResponse {

    private String contractId;
    private String signatureFlowStatus;
    private Integer currentSigningOrder;
    private List<ExternalSigner> externalSigners;
    private List<InternalSigner> internalSigners;
    private List<PartyCompletion> partyCompletions;
    private int version;
}

