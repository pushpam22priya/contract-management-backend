package com.costacloud.contractmanagement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(
    name = "AuthRequest",
    description = "Request body for login and auto-registration. If the email is new, the account is created automatically."
)
public class AuthRequest {

    @Schema(
        description = "User's email address. Used as the unique identifier.",
        example = "user@gmail.com",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @Schema(
        description = "User's password. Stored as a BCrypt hash — never in plain text. Minimum 4 characters.",
        example = "secret123",
        requiredMode = Schema.RequiredMode.REQUIRED,
        minLength = 4
    )
    @NotBlank(message = "Password is required")
    @Size(min = 4, message = "Password must be at least 4 characters")
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
