package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.model.RevokedToken;
import com.costacloud.contractmanagement.repository.RevokedTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBlacklistService")
class TokenBlacklistServiceTest {

    @Mock RevokedTokenRepository revokedTokenRepository;
    @Mock JwtService             jwtService;

    @InjectMocks TokenBlacklistService tokenBlacklistService;

    private static final String TOKEN = "valid.jwt.token";
    private static final String JTI   = "550e8400-e29b-41d4-a716-446655440000";

    @BeforeEach
    void stubDefaults() {
        lenient().when(jwtService.extractJti(TOKEN)).thenReturn(JTI);
        lenient().when(jwtService.extractExpiration(TOKEN)).thenReturn(new Date(System.currentTimeMillis() + 86_400_000));
        lenient().when(revokedTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // revokeToken
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeToken")
    class RevokeToken {

        @Test
        @DisplayName("calls revokedTokenRepository.save exactly once")
        void shouldCallSave_exactlyOnce() {
            tokenBlacklistService.revokeToken(TOKEN);

            verify(revokedTokenRepository, times(1)).save(any(RevokedToken.class));
        }

        @Test
        @DisplayName("saves the JTI extracted from the token")
        void shouldSave_correctJti() {
            tokenBlacklistService.revokeToken(TOKEN);

            ArgumentCaptor<RevokedToken> captor = ArgumentCaptor.forClass(RevokedToken.class);
            verify(revokedTokenRepository).save(captor.capture());
            assertEquals(JTI, captor.getValue().getJti());
        }

        @Test
        @DisplayName("saves the expiration date extracted from the token")
        void shouldSave_expirationDate() {
            Date expiry = new Date(System.currentTimeMillis() + 86_400_000);
            when(jwtService.extractExpiration(TOKEN)).thenReturn(expiry);

            tokenBlacklistService.revokeToken(TOKEN);

            ArgumentCaptor<RevokedToken> captor = ArgumentCaptor.forClass(RevokedToken.class);
            verify(revokedTokenRepository).save(captor.capture());
            assertEquals(expiry, captor.getValue().getExpiresAt());
        }

        @Test
        @DisplayName("saves a non-null expiresAt so the MongoDB TTL index can clean it up")
        void shouldSave_nonNull_expiresAt() {
            tokenBlacklistService.revokeToken(TOKEN);

            ArgumentCaptor<RevokedToken> captor = ArgumentCaptor.forClass(RevokedToken.class);
            verify(revokedTokenRepository).save(captor.capture());
            assertNotNull(captor.getValue().getExpiresAt());
        }

        @Test
        @DisplayName("extracts JTI from the token — does not hardcode or guess it")
        void shouldExtractJti_fromToken() {
            tokenBlacklistService.revokeToken(TOKEN);

            verify(jwtService, times(1)).extractJti(TOKEN);
        }

        @Test
        @DisplayName("extracts expiration from the token — does not hardcode it")
        void shouldExtractExpiration_fromToken() {
            tokenBlacklistService.revokeToken(TOKEN);

            verify(jwtService, times(1)).extractExpiration(TOKEN);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRevoked
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isRevoked")
    class IsRevoked {

        @Test
        @DisplayName("returns true when the token's JTI is in the blacklist")
        void shouldReturnTrue_whenJtiRevoked() {
            when(revokedTokenRepository.existsByJti(JTI)).thenReturn(true);

            assertTrue(tokenBlacklistService.isRevoked(TOKEN));
        }

        @Test
        @DisplayName("returns false when the token's JTI is not in the blacklist")
        void shouldReturnFalse_whenJtiNotRevoked() {
            when(revokedTokenRepository.existsByJti(JTI)).thenReturn(false);

            assertFalse(tokenBlacklistService.isRevoked(TOKEN));
        }

        @Test
        @DisplayName("checks the blacklist using the JTI extracted from the token")
        void shouldCheckBlacklist_usingExtractedJti() {
            when(revokedTokenRepository.existsByJti(JTI)).thenReturn(false);

            tokenBlacklistService.isRevoked(TOKEN);

            verify(revokedTokenRepository, times(1)).existsByJti(JTI);
        }

        @Test
        @DisplayName("returns false — does not throw — when extractJti raises an exception")
        void shouldReturnFalse_whenExtractJtiThrows() {
            when(jwtService.extractJti(TOKEN)).thenThrow(new RuntimeException("malformed token"));

            assertFalse(tokenBlacklistService.isRevoked(TOKEN));
        }

        @Test
        @DisplayName("does not call the repository when extractJti throws an exception")
        void shouldNotCallRepo_whenExtractJtiThrows() {
            when(jwtService.extractJti(TOKEN)).thenThrow(new RuntimeException("malformed token"));

            tokenBlacklistService.isRevoked(TOKEN);

            verify(revokedTokenRepository, never()).existsByJti(anyString());
        }

        @Test
        @DisplayName("returns false — does not throw — when the repository raises an exception")
        void shouldReturnFalse_whenRepoThrows() {
            when(revokedTokenRepository.existsByJti(JTI)).thenThrow(new RuntimeException("DB down"));

            assertFalse(tokenBlacklistService.isRevoked(TOKEN));
        }
    }
}
