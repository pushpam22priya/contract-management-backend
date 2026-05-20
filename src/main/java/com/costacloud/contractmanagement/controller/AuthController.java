package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.AuthRequest;
import com.costacloud.contractmanagement.dto.AuthResponse;
import com.costacloud.contractmanagement.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Handles login and auto-registration. Returns a JWT token on success.")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
        summary = "Login or Auto-Register",
        description = "If the email is new, the account is created automatically and isNewUser is true. " +
                      "If the email exists, the password is validated and isNewUser is false. " +
                      "Wrong password returns 401. " +
                      "Email admin@gmail.com receives ADMIN role. All others receive USER role. " +
                      "Token expires in 24 hours."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Authentication successful",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AuthResponse.class),
                examples = {
                    @ExampleObject(
                        name = "New User",
                        value = "{\"token\": \"eyJhbGci...\", \"email\": \"user@gmail.com\", \"newUser\": true, \"role\": \"USER\"}"
                    ),
                    @ExampleObject(
                        name = "Existing User",
                        value = "{\"token\": \"eyJhbGci...\", \"email\": \"user@gmail.com\", \"newUser\": false, \"role\": \"USER\"}"
                    ),
                    @ExampleObject(
                        name = "Admin",
                        value = "{\"token\": \"eyJhbGci...\", \"email\": \"admin@gmail.com\", \"newUser\": true, \"role\": \"ADMIN\"}"
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Validation failed - invalid email or short password",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"status\": 400, \"error\": \"Validation Failed\", \"fields\": {\"email\": \"Invalid email format\"}}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Wrong password for existing account",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"status\": 401, \"error\": \"Invalid password\"}"
                )
            )
        )
    })
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        AuthResponse response = authService.authenticate(request);
        return ResponseEntity.ok(response);
    }
}
