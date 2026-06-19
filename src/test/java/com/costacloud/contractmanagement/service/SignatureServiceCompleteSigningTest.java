package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.InternalSignCompleteRequest;
import com.costacloud.contractmanagement.dto.SignUploadCompleteRequest;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.ConflictException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureService - signer submits signature")
class SignatureServiceCompleteSigningTest {

    @Mock ContractRepository contractRepository;
    @Mock SignatureRequestRepository signatureRequestRepository;
    @Mock AutoAdvanceService autoAdvanceService;
    @Mock EmailService emailService;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks SignatureService signatureService;

    private static final String TOKEN       = "tok-sign-001";
    private static final String CONTRACT_ID = "contract-sign-01";
    private static final String SIGNER_EMAIL = "signer@client.com";

    @BeforeEach
    void injectValues() {
        ReflectionTestUtils.setField(signatureService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(signatureService, "baseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(signatureService, "expiryDays", 7);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private SignatureRequest pendingRequest() {
        SignatureRequest sr = new SignatureRequest();
        sr.setToken(TOKEN);
        sr.setContractId(CONTRACT_ID);
        sr.setSignerEmail(SIGNER_EMAIL);
        sr.setSignerName("Alice");
        sr.setStatus("pending");
        sr.setExpiresAt(LocalDateTime.now().plusDays(7));
        sr.setContractVersion(1);
        sr.setOrder(1);
        return sr;
    }

    private Contract inSignatureContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setStatus(ContractStatus.IN_SIGNATURE);
        c.setVersion(1);
        c.setCreatedBy("owner@test.com");
        c.setTitle("Service Agreement");

        ExternalSigner signer = new ExternalSigner();
        signer.setToken(TOKEN);
        signer.setEmail(SIGNER_EMAIL);
        signer.setPartyId("party_1");
        signer.setOrder(1);
        signer.setStatus("unlocked");
        signer.setSigningRound(1);

        c.setExternalSigners(new ArrayList<>(List.of(signer)));
        c.setPartyCompletions(new ArrayList<>());
        return c;
    }

    /** A valid final-submit request with one PDF part */
    private SignUploadCompleteRequest finalSubmitRequest() {
        SignUploadCompleteRequest req = new SignUploadCompleteRequest();
        req.setUploadId("upload-abc");
        req.setAutoSave(false);

        SignUploadCompleteRequest.Part part = new SignUploadCompleteRequest.Part();
        part.setPartNumber(1);
        part.setETag("etag-001");
        req.setParts(List.of(part));
        return req;
    }

    /** A valid auto-save request (no final commit) */
    private SignUploadCompleteRequest autoSaveRequest() {
        SignUploadCompleteRequest req = new SignUploadCompleteRequest();
        req.setAutoSave(true);
        // no uploadId / parts — just saving progress
        return req;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXTERNAL SIGNER — completeSignedPdfUpload
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("External signer — final submit validation")
    class ExternalValidation {

        @Test
        @DisplayName("throws NotFoundException when token does not exist")
        void shouldThrow_whenTokenNotFound() {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class, () ->
                signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest())
            );
        }

        @Test
        @DisplayName("throws BadRequestException when signer already submitted")
        void shouldThrow_whenAlreadySigned() {
            SignatureRequest sr = pendingRequest();
            sr.setStatus("signed");
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest())
            );
            assertEquals("You have already submitted your signature", ex.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when signing link is expired")
        void shouldThrow_whenTokenExpired() {
            SignatureRequest sr = pendingRequest();
            sr.setExpiresAt(LocalDateTime.now().minusHours(1));
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest())
            );
            assertTrue(ex.getMessage().contains("expired"));
        }

        @Test
        @DisplayName("throws ConflictException when contract was modified after signer opened the link")
        void shouldThrow_whenContractVersionMismatch() {
            SignatureRequest sr = pendingRequest();
            sr.setContractVersion(1);

            Contract c = inSignatureContract();
            c.setVersion(2);  // contract updated while signer was filling in the form

            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(ConflictException.class, () ->
                signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest())
            );
        }

        @Test
        @DisplayName("throws BadRequestException when no PDF parts provided on final submit")
        void shouldThrow_whenNoPdfParts() {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inSignatureContract()));

            SignUploadCompleteRequest req = new SignUploadCompleteRequest();
            req.setAutoSave(false);
            // no uploadId, no parts

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeSignedPdfUpload(TOKEN, req)
            );
            assertTrue(ex.getMessage().contains("Signed PDF upload is required"));
        }
    }

    @Nested
    @DisplayName("External signer — final submit happy path")
    class ExternalHappyPath {

        @BeforeEach
        void stubMinio() throws Exception {
            lenient().doNothing().when(customMinioClient)
                .finishMultipartUpload(any(), any(), any(), any());
            lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(signatureRequestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("marks SignatureRequest status as signed")
        void shouldMark_signatureRequest_asSigned() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inSignatureContract()));

            signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest());

            ArgumentCaptor<SignatureRequest> srCaptor = ArgumentCaptor.forClass(SignatureRequest.class);
            verify(signatureRequestRepository, atLeastOnce()).save(srCaptor.capture());

            SignatureRequest saved = srCaptor.getAllValues().stream()
                    .filter(s -> "signed".equals(s.getStatus())).findFirst().orElseThrow();
            assertNotNull(saved.getSignedAt());
        }

        @Test
        @DisplayName("marks the matching ExternalSigner as completed")
        void shouldMark_externalSigner_asCompleted() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inSignatureContract()));

            signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest());

            Contract saved = captureLastSavedContract();
            ExternalSigner signer = saved.getExternalSigners().stream()
                    .filter(s -> TOKEN.equals(s.getToken())).findFirst().orElseThrow();

            assertEquals("completed", signer.getStatus());
            assertNotNull(signer.getCompletedAt());
        }

        @Test
        @DisplayName("increments contract version after final submit")
        void shouldIncrement_contractVersion() throws Exception {
            Contract c = inSignatureContract();
            c.setVersion(1);
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest());

            assertEquals(2, captureLastSavedContract().getVersion());
        }

        @Test
        @DisplayName("triggers AutoAdvanceService after final submit")
        void shouldTrigger_autoAdvance() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inSignatureContract()));

            signatureService.completeSignedPdfUpload(TOKEN, finalSubmitRequest());

            verify(autoAdvanceService, times(1)).advance(CONTRACT_ID);
        }
    }

    @Nested
    @DisplayName("External signer — auto-save")
    class AutoSave {

        @BeforeEach
        void stub() {
            lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(signatureRequestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("saves progress without marking as signed on auto-save")
        void shouldSave_withoutMarkingAsSigned() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inSignatureContract()));

            signatureService.completeSignedPdfUpload(TOKEN, autoSaveRequest());

            // SignatureRequest must NOT be marked as signed
            ArgumentCaptor<SignatureRequest> captor = ArgumentCaptor.forClass(SignatureRequest.class);
            verify(signatureRequestRepository, atLeastOnce()).save(captor.capture());
            captor.getAllValues().forEach(sr ->
                assertNotEquals("signed", sr.getStatus())
            );
        }

        @Test
        @DisplayName("throws BadRequestException when link is expired even on auto-save")
        void shouldThrow_whenExpired_onAutoSave() {
            SignatureRequest sr = pendingRequest();
            sr.setExpiresAt(LocalDateTime.now().minusDays(1));
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeSignedPdfUpload(TOKEN, autoSaveRequest())
            );
            assertTrue(ex.getMessage().contains("expired"));
        }

        @Test
        @DisplayName("does NOT trigger auto-advance on auto-save")
        void shouldNotTrigger_autoAdvance_onAutoSave() throws Exception {
            when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(pendingRequest()));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inSignatureContract()));

            signatureService.completeSignedPdfUpload(TOKEN, autoSaveRequest());

            verify(autoAdvanceService, never()).advance(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL SIGNER — completeInternalSigning
    // ─────────────────────────────────────────────────────────────────────────

    private Contract contractWithInternalSigner(String status) {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setStatus(ContractStatus.IN_SIGNATURE);
        c.setVersion(1);
        c.setCreatedBy("owner@test.com");
        c.setTitle("Service Agreement");

        InternalSigner signer = new InternalSigner();
        signer.setEmail(SIGNER_EMAIL);
        signer.setPartyId("party_2");
        signer.setOrder(1);
        signer.setStatus(status);
        signer.setSigningRound(1);

        c.setInternalSigners(new ArrayList<>(List.of(signer)));
        c.setPartyCompletions(new ArrayList<>());
        return c;
    }

    private InternalSignCompleteRequest internalRequest() {
        InternalSignCompleteRequest req = new InternalSignCompleteRequest();
        req.setSignerEmail(SIGNER_EMAIL);
        req.setUploadId("upload-int-001");

        InternalSignCompleteRequest.Part part = new InternalSignCompleteRequest.Part();
        part.setPartNumber(1);
        part.setETag("etag-int-001");
        req.setParts(List.of(part));
        return req;
    }

    @Nested
    @DisplayName("Internal signer — validation")
    class InternalValidation {

        @Test
        @DisplayName("throws NotFoundException when caller is not assigned as a signer")
        void shouldThrow_whenSignerNotAssigned() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("unlocked")));

            assertThrows(NotFoundException.class, () ->
                signatureService.completeInternalSigning(
                    CONTRACT_ID, internalRequest(), "other@person.com")  // wrong email
            );
        }

        @Test
        @DisplayName("throws BadRequestException when internal signer already completed")
        void shouldThrow_whenAlreadyCompleted() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("completed")));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeInternalSigning(CONTRACT_ID, internalRequest(), SIGNER_EMAIL)
            );
            assertTrue(ex.getMessage().contains("already completed"));
        }

        @Test
        @DisplayName("throws BadRequestException when it is not yet the signer's turn")
        void shouldThrow_whenNotYetUnlocked() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("pending")));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeInternalSigning(CONTRACT_ID, internalRequest(), SIGNER_EMAIL)
            );
            assertTrue(ex.getMessage().contains("not yet your turn"));
        }

        @Test
        @DisplayName("throws BadRequestException when no PDF parts are provided")
        void shouldThrow_whenNoPdfParts() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("unlocked")));

            InternalSignCompleteRequest req = new InternalSignCompleteRequest();
            req.setSignerEmail(SIGNER_EMAIL);
            // no uploadId, no parts

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.completeInternalSigning(CONTRACT_ID, req, SIGNER_EMAIL)
            );
            assertTrue(ex.getMessage().contains("Signed PDF is required"));
        }
    }

    @Nested
    @DisplayName("Internal signer — happy path")
    class InternalHappyPath {

        @BeforeEach
        void stub() throws Exception {
            lenient().doNothing().when(customMinioClient)
                .finishMultipartUpload(any(), any(), any(), any());
            lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(signatureRequestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("marks the internal signer as completed")
        void shouldMark_internalSigner_asCompleted() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("unlocked")));

            signatureService.completeInternalSigning(CONTRACT_ID, internalRequest(), SIGNER_EMAIL);

            Contract saved = captureLastSavedContract();
            InternalSigner signer = saved.getInternalSigners().get(0);
            assertEquals("completed", signer.getStatus());
            assertNotNull(signer.getCompletedAt());
        }

        @Test
        @DisplayName("increments contract version after internal signing")
        void shouldIncrement_contractVersion() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("unlocked")));

            signatureService.completeInternalSigning(CONTRACT_ID, internalRequest(), SIGNER_EMAIL);

            assertEquals(2, captureLastSavedContract().getVersion());
        }

        @Test
        @DisplayName("triggers AutoAdvanceService after internal signing")
        void shouldTrigger_autoAdvance() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(contractWithInternalSigner("unlocked")));

            signatureService.completeInternalSigning(CONTRACT_ID, internalRequest(), SIGNER_EMAIL);

            verify(autoAdvanceService, times(1)).advance(CONTRACT_ID);
        }
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private Contract captureLastSavedContract() {
        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
