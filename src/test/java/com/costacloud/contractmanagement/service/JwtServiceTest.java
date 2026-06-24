package com.costacloud.contractmanagement.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtService")
class JwtServiceTest {

    private JwtService jwtService;

    // 64 hex chars = 32 bytes — valid HMAC-SHA-256 key
    private static final String TEST_SECRET =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long   EXPIRY_MS   = 86_400_000L; // 24 h

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret",     TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", EXPIRY_MS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateToken
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {

        @Test
        @DisplayName("returns a non-blank token string")
        void shouldReturnNonBlankToken() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertNotNull(token);
            assertFalse(token.isBlank());
        }

        @Test
        @DisplayName("token is a three-part JWT (header.payload.signature)")
        void shouldReturnWellFormedJwt() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertEquals(3, token.split("\\.").length);
        }

        @Test
        @DisplayName("tokens for different emails are not identical")
        void shouldProduceDifferentTokens_forDifferentEmails() {
            String t1 = jwtService.generateToken("alice@test.com", "USER");
            String t2 = jwtService.generateToken("bob@test.com",   "USER");

            assertNotEquals(t1, t2);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractEmail
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractEmail")
    class ExtractEmail {

        @Test
        @DisplayName("returns the email embedded in the token")
        void shouldExtractEmail() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertEquals("user@test.com", jwtService.extractEmail(token));
        }

        @Test
        @DisplayName("returns the correct email when multiple tokens exist")
        void shouldExtractCorrectEmail_forEachToken() {
            String t1 = jwtService.generateToken("alice@test.com", "USER");
            String t2 = jwtService.generateToken("bob@test.com",   "ADMIN");

            assertEquals("alice@test.com", jwtService.extractEmail(t1));
            assertEquals("bob@test.com",   jwtService.extractEmail(t2));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractRole
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractRole")
    class ExtractRole {

        @Test
        @DisplayName("returns USER role from token")
        void shouldExtractUserRole() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertEquals("USER", jwtService.extractRole(token));
        }

        @Test
        @DisplayName("returns ADMIN role from token")
        void shouldExtractAdminRole() {
            String token = jwtService.generateToken("admin@test.com", "ADMIN");

            assertEquals("ADMIN", jwtService.extractRole(token));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractJti
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractJti")
    class ExtractJti {

        @Test
        @DisplayName("returns a non-null JTI from a freshly generated token")
        void shouldReturnNonNull_jti() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertNotNull(jwtService.extractJti(token));
        }

        @Test
        @DisplayName("JTI is a non-blank string")
        void shouldReturnNonBlank_jti() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertFalse(jwtService.extractJti(token).isBlank());
        }

        @Test
        @DisplayName("each generated token has a unique JTI")
        void shouldProduceUniqueJti_perToken() {
            String t1 = jwtService.generateToken("user@test.com", "USER");
            String t2 = jwtService.generateToken("user@test.com", "USER");

            assertNotEquals(jwtService.extractJti(t1), jwtService.extractJti(t2));
        }

        @Test
        @DisplayName("extractJti is consistent — same token returns same JTI each call")
        void shouldReturnSameJti_forSameToken() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertEquals(jwtService.extractJti(token), jwtService.extractJti(token));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractExpiration
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractExpiration")
    class ExtractExpiration {

        @Test
        @DisplayName("returns a non-null expiration date")
        void shouldReturnNonNull_expiration() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertNotNull(jwtService.extractExpiration(token));
        }

        @Test
        @DisplayName("expiration is in the future for a freshly generated token")
        void shouldReturnFutureDate_forFreshToken() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertTrue(jwtService.extractExpiration(token).after(new java.util.Date()));
        }

        @Test
        @DisplayName("expiration is approximately now + expiry duration")
        void shouldReturnDate_approximatelyNowPlusExpiry() {
            String token = jwtService.generateToken("user@test.com", "USER");

            long expiryMs = jwtService.extractExpiration(token).getTime();
            long expectedMs = System.currentTimeMillis() + EXPIRY_MS;

            // Allow 5-second tolerance for test execution time
            assertTrue(Math.abs(expiryMs - expectedMs) < 5_000);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isTokenValid
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isTokenValid")
    class IsTokenValid {

        @Test
        @DisplayName("returns true for a freshly generated token")
        void shouldReturnTrue_forValidToken() {
            String token = jwtService.generateToken("user@test.com", "USER");

            assertTrue(jwtService.isTokenValid(token));
        }

        @Test
        @DisplayName("returns false for a completely garbage string")
        void shouldReturnFalse_forGarbageString() {
            assertFalse(jwtService.isTokenValid("this.is.garbage"));
        }

        @Test
        @DisplayName("returns false for an empty string")
        void shouldReturnFalse_forEmptyString() {
            assertFalse(jwtService.isTokenValid(""));
        }

        @Test
        @DisplayName("returns false for a token signed with a different secret")
        void shouldReturnFalse_whenSignedWithDifferentSecret() {
            // Create a second JwtService with a different secret
            JwtService other = new JwtService();
            ReflectionTestUtils.setField(other, "secret",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
            ReflectionTestUtils.setField(other, "expiration", EXPIRY_MS);

            String foreignToken = other.generateToken("user@test.com", "USER");

            assertFalse(jwtService.isTokenValid(foreignToken));
        }

        @Test
        @DisplayName("returns false for an expired token")
        void shouldReturnFalse_forExpiredToken() {
            // Set expiration to -1000 ms so the token is already expired at creation
            ReflectionTestUtils.setField(jwtService, "expiration", -1000L);
            String expiredToken = jwtService.generateToken("user@test.com", "USER");

            assertFalse(jwtService.isTokenValid(expiredToken));
        }

        @Test
        @DisplayName("returns false for a tampered payload (modified base64 segment)")
        void shouldReturnFalse_forTamperedToken() {
            String token  = jwtService.generateToken("user@test.com", "USER");
            String[] parts = token.split("\\.");
            // Replace the payload segment with arbitrary base64
            String tampered = parts[0] + ".dGFtcGVyZWQ.TAMPERED_SIG";

            assertFalse(jwtService.isTokenValid(tampered));
        }
    }
}
