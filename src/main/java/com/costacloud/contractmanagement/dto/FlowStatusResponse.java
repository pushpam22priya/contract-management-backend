package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.model.Party;
import com.costacloud.contractmanagement.model.WorkflowParticipant;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FlowStatusResponse {
    private String contractId;
    private ContractStatus status;
    private Integer currentParticipantOrder;
    private List<WorkflowParticipant> participants;
    private boolean externalSigningIncluded;
    private boolean orgFieldsComplete;
    private List<String> unfilledOrgFields;
    private List<Party> parties;
    private List<Map<String, Object>> formFields;
    private String xfdfData;
}
