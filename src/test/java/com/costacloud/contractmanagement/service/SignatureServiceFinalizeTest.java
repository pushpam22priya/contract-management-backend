package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureService - finalizeContract")
class SignatureServiceFinalizeTest {

    @Mock ContractRepository contractRepository;
    @Mock SignatureRequestRepository signatureRequestRepository;
    @Mock AutoAdvanceService autoAdvanceService;
    @Mock EmailService emailService;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks SignatureService signatureService;

    private static final String CONTRACT_ID = "contract-final-01";
    private static final String OWNER_EMAIL  = "owner@test.com";

    @BeforeEach
    void injectValues() {
        ReflectionTestUtils.setField(signatureService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(signatureService, "baseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(signatureService, "expiryDays", 7);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Contract where all parties have signed — ready to finalize */
    private Contract allCompletedContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER_EMAIL);
        c.setTitle("Service Agreement");
        c.setStatus(ContractStatus.SIGNED_BY_EVERYONE);
        c.setSignatureFlowStatus("all_completed");
        c.setVersion(3);

        ExternalSigner ext = new ExternalSigner();
        ext.setEmail("buyer@client.com");
        ext.setName("Bob Buyer");
        ext.setStatus("completed");

        InternalSigner intern = new InternalSigner();
        intern.setEmail("seller@company.com");
        intern.setName("Sally Seller");
        intern.setStatus("completed");

        c.setExternalSigners(new ArrayList<>(List.of(ext)));
        c.setInternalSigners(new ArrayList<>(List.of(intern)));
        return c;
    }

    /** Mock MinIO read — returns fake PDF bytes */
    private void stubMinioReadSuccess() throws Exception {
        GetObjectResponse mockResp = mock(GetObjectResponse.class);
        when(mockResp.readAllBytes()).thenReturn(new byte[]{37, 80, 68, 70}); // %PDF
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — Validation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("throws NotFoundException when contract does not exist")
        void shouldThrow_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class, () ->
                signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL)
            );
        }

        @Test
        @DisplayName("throws NotFoundException when caller is not the contract owner")
        void shouldThrow_whenCallerIsNotOwner() {
            Contract c = allCompletedContract();
            c.setCreatedBy("someone-else@test.com");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(NotFoundException.class, () ->
                signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL)
            );
        }

        @Test
        @DisplayName("throws BadRequestException when contract is already finalized")
        void shouldThrow_whenAlreadyFinalized() {
            Contract c = allCompletedContract();
            c.setSignatureFlowStatus("finalized");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL)
            );
            assertEquals("Contract has already been finalized", ex.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when not all parties have signed yet")
        void shouldThrow_whenSigningStillInProgress() {
            Contract c = allCompletedContract();
            c.setSignatureFlowStatus("pending_signatures");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL)
            );
            assertTrue(ex.getMessage().contains("not all parties have signed yet"));
        }

        @Test
        @DisplayName("throws NotFoundException when signed PDF is missing from MinIO")
        void shouldThrow_whenSignedPdfMissingFromMinio() {
            // minioClient.getObject() is not stubbed → returns null
            // null.readAllBytes() → NPE → caught → NotFoundException("PDF not found")
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            assertThrows(NotFoundException.class, () ->
                signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL)
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path — state changes after finalization")
    class HappyPath {

        @BeforeEach
        void stub() throws Exception {
            stubMinioReadSuccess();
            lenient().when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
            lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().doNothing().when(emailService)
                .sendSignedCopyEmail(any(), any(), any(), any());
        }

        @Test
        @DisplayName("sets contract status to SIGNED")
        void shouldSetStatus_toSigned() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            assertEquals(ContractStatus.SIGNED, captureLastSavedContract().getStatus());
        }

        @Test
        @DisplayName("sets signatureFlowStatus to finalized")
        void shouldSetFlowStatus_toFinalized() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            assertEquals("finalized", captureLastSavedContract().getSignatureFlowStatus());
        }

        @Test
        @DisplayName("sets finalPdfKey on the contract")
        void shouldSetFinalPdfKey() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            String expectedKey = "contracts/" + CONTRACT_ID + "_final.pdf";
            assertEquals(expectedKey, captureLastSavedContract().getFinalPdfKey());
        }

        @Test
        @DisplayName("saves the contract after finalizing")
        void shouldSave_contractAfterFinalization() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            verify(contractRepository, times(1)).save(any(Contract.class));
        }

        @Test
        @DisplayName("sends signed copy email to every external signer")
        void shouldSendEmail_toAllExternalSigners() throws Exception {
            Contract c = allCompletedContract();
            // Two external signers
            ExternalSigner ext2 = new ExternalSigner();
            ext2.setEmail("buyer2@client.com");
            ext2.setName("Carol Buyer");
            ext2.setStatus("completed");
            c.getExternalSigners().add(ext2);

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            // One external signer in base helper + one added above = 2 external emails
            verify(emailService, times(2))
                .sendSignedCopyEmail(
                    argThat(email -> email.contains("client.com")),
                    any(), any(), any()
                );
        }

        @Test
        @DisplayName("sends signed copy email to every internal signer")
        void shouldSendEmail_toAllInternalSigners() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            verify(emailService, times(1))
                .sendSignedCopyEmail(
                    eq("seller@company.com"), eq("Sally Seller"), any(), any()
                );
        }

        @Test
        @DisplayName("sends emails to both external and internal signers combined")
        void shouldSendEmail_toAllSigners_combined() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            // allCompletedContract() has 1 external + 1 internal = 2 total emails
            verify(emailService, times(2))
                .sendSignedCopyEmail(any(), any(), any(), any());
        }

        @Test
        @DisplayName("reads the signed PDF from MinIO using the correct key")
        void shouldRead_signedPdfFromMinio_withCorrectKey() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            verify(minioClient).getObject(argThat(args ->
                args.object().equals("contracts/" + CONTRACT_ID + "_signed.pdf")
            ));
        }

        @Test
        @DisplayName("writes the final PDF to MinIO using the correct key")
        void shouldWrite_finalPdfToMinio_withCorrectKey() throws Exception {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(allCompletedContract()));

            signatureService.finalizeContract(CONTRACT_ID, OWNER_EMAIL);

            verify(minioClient).putObject(argThat(args ->
                args.object().equals("contracts/" + CONTRACT_ID + "_final.pdf")
            ));
        }
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private Contract captureLastSavedContract() {
        org.mockito.ArgumentCaptor<Contract> captor =
            org.mockito.ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
