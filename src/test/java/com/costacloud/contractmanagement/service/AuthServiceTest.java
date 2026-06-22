package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.AuthRequest;
import com.costacloud.contractmanagement.dto.AuthResponse;
import com.costacloud.contractmanagement.model.User;
import com.costacloud.contractmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService - authenticate")
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock JwtService jwtService;
    @Mock BCryptPasswordEncoder passwordEncoder;

    @InjectMocks AuthService authService;

    private static final String USER_EMAIL   = "user@test.com";
    private static final String ADMIN_EMAIL  = "admin@gmail.com";
    private static final String RAW_PASSWORD = "secret123";
    private static final String HASHED_PW   = "$2a$10$hashed";
    private static final String FAKE_TOKEN   = "jwt.token.here";

    private AuthRequest request(String email) {
        AuthRequest req = new AuthRequest();
        req.setEmail(email);
        req.setPassword(RAW_PASSWORD);
        return req;
    }

    private User existingUser(String email, User.Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(HASHED_PW);
        u.setRole(role);
        return u;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — New user registration
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("New user registration")
    class NewUser {

        @BeforeEach
        void stubNewUser() {
            lenient().when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.empty());
            lenient().when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(HASHED_PW);
            lenient().when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(jwtService.generateToken(eq(USER_EMAIL), anyString())).thenReturn(FAKE_TOKEN);
        }

        @Test
        @DisplayName("saves the new user to the repository")
        void shouldSaveNewUser_toRepository() {
            authService.authenticate(request(USER_EMAIL));

            verify(userRepository, times(1)).save(any(User.class));
        }

        @Test
        @DisplayName("encodes the password before saving — never stores plain text")
        void shouldEncodePassword_beforeSaving() {
            authService.authenticate(request(USER_EMAIL));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertEquals(HASHED_PW, captor.getValue().getPassword());
        }

        @Test
        @DisplayName("assigns USER role to a non-admin email")
        void shouldAssignUserRole_toRegularEmail() {
            authService.authenticate(request(USER_EMAIL));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertEquals(User.Role.USER, captor.getValue().getRole());
        }

        @Test
        @DisplayName("assigns ADMIN role when email is admin@gmail.com")
        void shouldAssignAdminRole_toAdminEmail() {
            when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());
            when(jwtService.generateToken(eq(ADMIN_EMAIL), eq("ADMIN"))).thenReturn(FAKE_TOKEN);

            authService.authenticate(request(ADMIN_EMAIL));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertEquals(User.Role.ADMIN, captor.getValue().getRole());
        }

        @Test
        @DisplayName("sets isNewUser = true in the response")
        void shouldSetIsNewUser_true() {
            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertTrue(response.isNewUser());
        }

        @Test
        @DisplayName("returns the JWT token in the response")
        void shouldReturnToken_inResponse() {
            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertEquals(FAKE_TOKEN, response.getToken());
        }

        @Test
        @DisplayName("returns the registered email in the response")
        void shouldReturnEmail_inResponse() {
            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertEquals(USER_EMAIL, response.getEmail());
        }

        @Test
        @DisplayName("returns the role as a string in the response")
        void shouldReturnRole_inResponse() {
            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertEquals("USER", response.getRole());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — Existing user login
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Existing user login")
    class ExistingUser {

        @BeforeEach
        void stubExistingUser() {
            User user = existingUser(USER_EMAIL, User.Role.USER);
            when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
            lenient().when(jwtService.generateToken(eq(USER_EMAIL), eq("USER"))).thenReturn(FAKE_TOKEN);
        }

        @Test
        @DisplayName("returns a token when password matches")
        void shouldReturnToken_whenPasswordCorrect() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(true);

            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertEquals(FAKE_TOKEN, response.getToken());
        }

        @Test
        @DisplayName("sets isNewUser = false for an existing user")
        void shouldSetIsNewUser_false() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(true);

            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertFalse(response.isNewUser());
        }

        @Test
        @DisplayName("returns the correct email in the response")
        void shouldReturnEmail_inResponse() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(true);

            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertEquals(USER_EMAIL, response.getEmail());
        }

        @Test
        @DisplayName("returns the correct role in the response")
        void shouldReturnRole_inResponse() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(true);

            AuthResponse response = authService.authenticate(request(USER_EMAIL));

            assertEquals("USER", response.getRole());
        }

        @Test
        @DisplayName("never saves user to repository on login")
        void shouldNotSave_existingUser() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(true);

            authService.authenticate(request(USER_EMAIL));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws RuntimeException when password is wrong")
        void shouldThrow_whenPasswordIsWrong() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(false);

            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                authService.authenticate(request(USER_EMAIL))
            );

            assertTrue(ex.getMessage().contains("password you entered is incorrect"));
        }

        @Test
        @DisplayName("does not generate a token when password is wrong")
        void shouldNotGenerateToken_whenPasswordIsWrong() {
            when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PW)).thenReturn(false);

            assertThrows(RuntimeException.class, () ->
                authService.authenticate(request(USER_EMAIL))
            );

            verify(jwtService, never()).generateToken(any(), any());
        }
    }
}
