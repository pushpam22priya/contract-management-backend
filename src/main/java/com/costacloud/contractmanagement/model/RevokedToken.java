package com.costacloud.contractmanagement.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "revoked_tokens")
@Data
public class RevokedToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String jti;

    // MongoDB TTL index — auto-deletes this document once expiresAt passes
    @Indexed(expireAfterSeconds = 0)
    private Date expiresAt;
}

