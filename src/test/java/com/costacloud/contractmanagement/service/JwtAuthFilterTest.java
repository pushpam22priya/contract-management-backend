package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.filter.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter - doFilterInternal")
class JwtAuthFilterTest {

    @Mock JwtService            jwtService;
    @Mock TokenBlacklistService tokenBlacklistService;

    @InjectMocks JwtAuthFilter jwtAuthFilter;

    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;
    @Mock FilterChain         filterChain;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String USER_EMAIL  = "user@test.com";
    private static final String USER_ROLE   = "USER";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — Requests without a valid Bearer header
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("No / invalid Authorization header")
    class NoHeader {

        @Test
        @DisplayName("passes through when Authorization header is absent")
        void shouldPassThrough_whenNoAuthHeader() throws Exception {
            when(request.getHeader("Authorization")).thenReturn(null);

            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("does not set SecurityContext when Authorization header is absent")
        void shouldNotSetSecurityContext_whenNoAuthHeader() throws Exception {
            when(request.getHeader("Authorization")).thenReturn(null);

            jwtAuthFilter.doFilter(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("passes through when Authorization header does not start with 'Bearer '")
        void shouldPassThrough_whenNotBearerPrefix() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("does not set SecurityContext when header is not a Bearer token")
        void shouldNotSetSecurityContext_whenNotBearerPrefix() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            jwtAuthFilter.doFilter(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — Valid and non-revoked Bearer token
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Valid and non-revoked Bearer token")
    class ValidToken {

        @BeforeEach
        void stubValidToken() {
            when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_TOKEN);
            when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
            when(tokenBlacklistService.isRevoked(VALID_TOKEN)).thenReturn(false);
            when(jwtService.extractEmail(VALID_TOKEN)).thenReturn(USER_EMAIL);
            when(jwtService.extractRole(VALID_TOKEN)).thenReturn(USER_ROLE);
        }

        @Test
        @DisplayName("sets the SecurityContext with the extracted email as principal")
        void shouldSetAuthentication_withEmail_asPrincipal() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertEquals(USER_EMAIL, auth.getPrincipal());
        }

        @Test
        @DisplayName("sets ROLE_USER authority in the SecurityContext")
        void shouldSetUserRoleAuthority_inSecurityContext() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertTrue(auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        }

        @Test
        @DisplayName("sets ROLE_ADMIN authority when token carries ADMIN role")
        void shouldSetAdminRoleAuthority_inSecurityContext() throws Exception {
            when(jwtService.extractRole(VALID_TOKEN)).thenReturn("ADMIN");

            jwtAuthFilter.doFilter(request, response, filterChain);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertTrue(auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        }

        @Test
        @DisplayName("still calls filterChain.doFilter after setting SecurityContext")
        void shouldCallFilterChain_afterSettingContext() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — Invalid or expired token
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Invalid or expired Bearer token")
    class InvalidToken {

        @BeforeEach
        void stubInvalidToken() {
            when(request.getHeader("Authorization")).thenReturn("Bearer bad.token.here");
            when(jwtService.isTokenValid("bad.token.here")).thenReturn(false);
        }

        @Test
        @DisplayName("does not set SecurityContext when token is invalid")
        void shouldNotSetSecurityContext_whenTokenInvalid() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("still passes through to the filter chain when token is invalid")
        void shouldCallFilterChain_evenWhenTokenInvalid() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("never calls extractEmail when token is invalid")
        void shouldNotExtractEmail_whenTokenInvalid() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(jwtService, never()).extractEmail(any());
        }

        @Test
        @DisplayName("never checks the blacklist when the token signature is already invalid")
        void shouldNotCheckBlacklist_whenTokenInvalid() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(tokenBlacklistService, never()).isRevoked(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — Revoked token (valid signature but in blacklist)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Revoked Bearer token")
    class RevokedToken {

        @BeforeEach
        void stubRevokedToken() {
            when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_TOKEN);
            when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
            when(tokenBlacklistService.isRevoked(VALID_TOKEN)).thenReturn(true);
        }

        @Test
        @DisplayName("does not set SecurityContext when token has been revoked")
        void shouldNotSetSecurityContext_whenTokenRevoked() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("still passes through to the filter chain when token is revoked")
        void shouldCallFilterChain_whenTokenRevoked() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(filterChain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("never calls extractEmail when the token is revoked")
        void shouldNotExtractEmail_whenTokenRevoked() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(jwtService, never()).extractEmail(any());
        }

        @Test
        @DisplayName("never calls extractRole when the token is revoked")
        void shouldNotExtractRole_whenTokenRevoked() throws Exception {
            jwtAuthFilter.doFilter(request, response, filterChain);

            verify(jwtService, never()).extractRole(any());
        }
    }
}
