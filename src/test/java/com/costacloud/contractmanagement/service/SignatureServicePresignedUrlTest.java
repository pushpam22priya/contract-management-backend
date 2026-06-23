package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.model.InternalSigner;
import com.costacloud.contractmanagement.model.SignatureRequest;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureService — presigned URLs")
class SignatureServicePresignedUrlTest {

    @Mock ContractRepository contractRepository;
    @Mock SignatureRequestRepository signatureRequestRepository;
    @Mock AutoAdvanceService autoAdvanceService;
    @Mock EmailService emailService;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks SignatureService signatureService;

    private static final String TOKEN        = "valid-token-xyz";
    private static final String CONTRACT_ID  = "contract-sig-01";
    private static final String INT_SIGNER   = "internal@test.com";
    private static final String FAKE_URL     = "https://minio.local/presigned?token=abc";

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(signatureService, "bucketName",           "test-bucket");
        ReflectionTestUtils.setField(signatureService, "baseUrl",              "https://app.test");
        ReflectionTestUtils.setField(signatureService, "expiryDays", 7);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Active (non-expired, non-signed) SignatureRequest */
    private SignatureRequest activeRequest() {
        SignatureRequest sr = new SignatureRequest();
        sr.setToken(TOKEN);
        sr.setContractId(CONTRACT_ID);
        sr.setStatus("pending");
        sr.setExpiresAt(LocalDateTime.now().plusDays(3));
        return sr;
    }

    /** Expired SignatureRequest */
    private SignatureRequest expiredRequest() {
        SignatureRequest sr = activeRequest();
        sr.setExpiresAt(LocalDateTime.now().minusDays(1));
        return sr;
    }

    /** SignatureRequest where the signer has already submitted */
    private SignatureRequest signedRequest() {
        SignatureRequest sr = activeRequest();
        sr.setStatus("signed");
        return sr;
    }

    /** Contract with an unlocked internal signer */
    private Contract contractWithUnlockedSigner() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setStatus(ContractStatus.IN_SIGNATURE);

        InternalSigner signer = new InternalSigner();
        signer.setEmail(INT_SIGNER);
        signer.setStatus("unlocked");
        c.setInternalSigners(List.of(signer));
        return c;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSigningPdfUrl — GET presigned URL for external signer to view the PDF
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSigningPdfUrl — external signer view URL")
    class GetSigningPdfUrl {

        @BeforeEach
        void stubMinio() throws Exception {
            lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                    .thenReturn(FAKE_URL);
        }

        @Test
        @DisplayName("returns presigned GET URL for a valid active token")
        void shouldReturn_url_forValidToken() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(activeRequest()));

            String url = signatureService.getSigningPdfUrl(TOKEN);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("calls minioClient.getPresignedObjectUrl exactly once for a valid token")
        void shouldCallMinio_exactlyOnce_forValidToken() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(activeRequest()));

            signatureService.getSigningPdfUrl(TOKEN);

            verify(minioClient, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("throws NotFoundException when the token does not exist")
        void shouldThrow_404_whenTokenNotFound() {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> signatureService.getSigningPdfUrl(TOKEN));
        }

        @Test
        @DisplayName("throws BadRequestException when the signer has already submitted their signature")
        void shouldThrow_400_whenAlreadySigned() {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(signedRequest()));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> signatureService.getSigningPdfUrl(TOKEN));

            assertTrue(ex.getMessage().contains("already submitted"));
        }

        @Test
        @DisplayName("throws BadRequestException when the signing link has expired")
        void shouldThrow_400_whenTokenExpired() {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(expiredRequest()));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> signatureService.getSigningPdfUrl(TOKEN));

            assertTrue(ex.getMessage().toLowerCase().contains("expired"));
        }

        @Test
        @DisplayName("does not call MinIO when the token is not found")
        void shouldNotCallMinio_whenTokenNotFound() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> signatureService.getSigningPdfUrl(TOKEN));

            verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("uses _signed.pdf key when the signed copy exists in MinIO")
        void shouldCheck_signedPdfKey_inMinio() throws Exception {
            // statObject does not throw by default → objectExistsInMinio returns true → uses _signed.pdf
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(activeRequest()));

            signatureService.getSigningPdfUrl(TOKEN);

            verify(minioClient, atLeastOnce()).statObject(any(StatObjectArgs.class));
        }

        @Test
        @DisplayName("falls back to original PDF key when signed copy does not exist in MinIO")
        void shouldFallback_toOriginalKey_whenSignedMissing() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(activeRequest()));
            doThrow(new RuntimeException("NoSuchKey")).when(minioClient).statObject(any(StatObjectArgs.class));

            String url = signatureService.getSigningPdfUrl(TOKEN);

            // Still returns a URL, now using the original key
            assertEquals(FAKE_URL, url);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generatePresignedSignPart — PUT presigned URL for external signer upload chunk
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generatePresignedSignPart — external signer upload part URL")
    class GeneratePresignedSignPart {

        @BeforeEach
        void stubMinio() throws Exception {
            lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                    .thenReturn(FAKE_URL);
        }

        @Test
        @DisplayName("returns presigned PUT URL for a valid active token")
        void shouldReturn_uploadUrl_forValidToken() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(activeRequest()));

            String url = signatureService.generatePresignedSignPart(TOKEN, "upload-id-1", 1);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("calls minioClient.getPresignedObjectUrl exactly once for a valid token")
        void shouldCallMinio_exactlyOnce() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(activeRequest()));

            signatureService.generatePresignedSignPart(TOKEN, "upload-id-1", 1);

            verify(minioClient, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("throws NotFoundException when the token does not exist")
        void shouldThrow_404_whenTokenNotFound() {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> signatureService.generatePresignedSignPart(TOKEN, "upload-id-1", 1));
        }

        @Test
        @DisplayName("throws BadRequestException when the signer has already submitted")
        void shouldThrow_400_whenAlreadySigned() {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(signedRequest()));

            assertThrows(BadRequestException.class,
                    () -> signatureService.generatePresignedSignPart(TOKEN, "upload-id-1", 1));
        }

        @Test
        @DisplayName("throws BadRequestException when the signing link has expired")
        void shouldThrow_400_whenTokenExpired() {
            when(signatureRequestRepository.findByToken(TOKEN))
                    .thenReturn(Optional.of(expiredRequest()));

            assertThrows(BadRequestException.class,
                    () -> signatureService.generatePresignedSignPart(TOKEN, "upload-id-1", 1));
        }

        @Test
        @DisplayName("does not call MinIO when the token validation fails")
        void shouldNotCallMinio_whenTokenInvalid() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> signatureService.generatePresignedSignPart(TOKEN, "upload-id-1", 1));

            verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generatePresignedInternalSignPart — PUT presigned URL for internal signer upload chunk
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generatePresignedInternalSignPart — internal signer upload part URL")
    class GeneratePresignedInternalSignPart {

        @BeforeEach
        void stubMinio() throws Exception {
            lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                    .thenReturn(FAKE_URL);
        }

        @Test
        @DisplayName("returns presigned PUT URL when internal signer is unlocked")
        void shouldReturn_uploadUrl_whenSignerUnlocked() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithUnlockedSigner()));

            String url = signatureService.generatePresignedInternalSignPart(
                    CONTRACT_ID, "upload-id-1", 1, INT_SIGNER);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("calls minioClient.getPresignedObjectUrl exactly once when signer is valid")
        void shouldCallMinio_exactlyOnce_whenSignerValid() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithUnlockedSigner()));

            signatureService.generatePresignedInternalSignPart(
                    CONTRACT_ID, "upload-id-1", 1, INT_SIGNER);

            verify(minioClient, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("throws NotFoundException when the contract does not exist")
        void shouldThrow_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> signatureService.generatePresignedInternalSignPart(
                            CONTRACT_ID, "upload-id-1", 1, INT_SIGNER));
        }

        @Test
        @DisplayName("throws NotFoundException when the caller is not an assigned internal signer")
        void shouldThrow_404_whenCallerNotASigner() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithUnlockedSigner()));

            assertThrows(NotFoundException.class,
                    () -> signatureService.generatePresignedInternalSignPart(
                            CONTRACT_ID, "upload-id-1", 1, "notasigner@test.com"));
        }

        @Test
        @DisplayName("throws BadRequestException when the internal signer has already completed their signature")
        void shouldThrow_400_whenSignerAlreadyCompleted() {
            Contract c = contractWithUnlockedSigner();
            c.getInternalSigners().get(0).setStatus("completed");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> signatureService.generatePresignedInternalSignPart(
                            CONTRACT_ID, "upload-id-1", 1, INT_SIGNER));
        }

        @Test
        @DisplayName("throws BadRequestException when the internal signer is still pending (not yet unlocked)")
        void shouldThrow_400_whenSignerStillPending() {
            Contract c = contractWithUnlockedSigner();
            c.getInternalSigners().get(0).setStatus("pending");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> signatureService.generatePresignedInternalSignPart(
                            CONTRACT_ID, "upload-id-1", 1, INT_SIGNER));
        }

        @Test
        @DisplayName("does not call MinIO when the signer guard fails")
        void shouldNotCallMinio_whenSignerGuardFails() throws Exception {
            Contract c = contractWithUnlockedSigner();
            c.getInternalSigners().get(0).setStatus("completed");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> signatureService.generatePresignedInternalSignPart(
                            CONTRACT_ID, "upload-id-1", 1, INT_SIGNER));

            verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }
    }
}
