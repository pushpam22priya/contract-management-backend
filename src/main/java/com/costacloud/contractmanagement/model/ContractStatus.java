package com.costacloud.contractmanagement.model;

public enum ContractStatus {
    DRAFT,
    IN_REVIEW,
    IN_APPROVAL,
    READY_FOR_SIGNATURE,
    IN_SIGNATURE,
    SIGNED_BY_EVERYONE,      // all parties signed, contractor hasn't finalized yet
    SIGNED,                  // contractor finalized — final PDF sent to all parties
    ACTIVE,
    EXPIRING,
    EXPIRED,
    TERMINATED,
    REJECTED_BY_REVIEWER,
    REJECTED_BY_APPROVER,
    REJECTED
}
