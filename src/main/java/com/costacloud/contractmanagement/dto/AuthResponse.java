package com.costacloud.contractmanagement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    name = "AuthResponse",
    description = "Response returned after successful authentication or auto-registration."
)
public class AuthResponse {

    @Schema(
        description = "JWT Bearer token. Include this in the Authorization header for all protected requests: 'Authorization: Bearer <token>'. Expires in 24 hours.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyQGdtYWlsLmNvbSIsInJvbGUiOiJVU0VSIn0.signature"
    )
    private String token;

    @Schema(
        description = "Authenticated user's email address.",
        example = "user@gmail.com"
    )
    private String email;

    @Schema(
        description = "Indicates whether this request triggered auto-registration. true = new account was created, false = existing account was logged in.",
        example = "true"
    )
    private boolean isNewUser;

    @Schema(
        description = "Role assigned to the user. USER gets standard access. ADMIN gets access to all routes including /admin/**.",
        example = "USER",
        allowableValues = {"USER", "ADMIN"}
    )
    private String role;

    public AuthResponse(String token, String email, boolean isNewUser, String role) {
        this.token = token;
        this.email = email;
        this.isNewUser = isNewUser;
        this.role = role;
    }

    public String getToken() { return token; }
    public String getEmail() { return email; }
    public boolean isNewUser() { return isNewUser; }
    public String getRole() { return role; }
}
