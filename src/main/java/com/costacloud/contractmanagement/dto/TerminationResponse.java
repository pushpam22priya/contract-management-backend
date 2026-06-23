package com.costacloud.contractmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TerminationResponse {
    private boolean success;
    private boolean alreadyTerminated;
}
