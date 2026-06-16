package com.costacloud.contractmanagement.dto;

import lombok.Data;

@Data
public class SignCompleteResponse {

    private boolean success;
    private String message;

    public static SignCompleteResponse ok(String message) {
        SignCompleteResponse r = new SignCompleteResponse();
        r.success = true;
        r.message = message;
        return r;
    }
}

