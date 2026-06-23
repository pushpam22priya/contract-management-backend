package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.ApproverInfo;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.model.InternalSigner;
import com.costacloud.contractmanagement.model.ReviewerInfo;
import com.costacloud.contractmanagement.repository.ContractRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractService — presigned URLs")
class ContractServicePresignedUrlTest {

    @Mock ContractRepository contractRepository;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks ContractService contractService;

    private static final String CONTRACT_ID  = "contract-url-01";
    private static final String OWNER        = "owner@test.com";
    private static final String REVIEWER     = "reviewer@test.com";
    private static final String APPROVER     = "approver@test.com";
    private static final String INT_SIGNER   = "signer@test.com";
    private static final String STRANGER     = "stranger@test.com";
    private static final String FAKE_URL     = "https://minio.local/presigned?token=abc";

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(contractService, "bucketName", "test-bucket");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Fully populated contract owned by OWNER, file uploaded, all role lists set */
    private Contract uploadedContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER);
        c.setStatus(ContractStatus.SIGNED);
        c.setStartDate(LocalDate.now().minusMonths(1));
        c.setEndDate(LocalDate.now().plusMonths(11));
        c.setFileUploaded(true);

        ReviewerInfo r = new ReviewerInfo();
        r.setEmail(REVIEWER);
        c.setReviewers(List.of(r));

        ApproverInfo a = new ApproverInfo();
        a.setEmail(APPROVER);
        c.setApprover(a);

        InternalSigner s = new InternalSigner();
        s.setEmail(INT_SIGNER);
        s.setStatus("unlocked");
        c.setInternalSigners(List.of(s));

        return c;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generatePresignedViewUrl — GET URL for viewing the contract PDF
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generatePresignedViewUrl")
    class GeneratePresignedViewUrl {

        @BeforeEach
        void stubMinio() throws Exception {
            lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                    .thenReturn(FAKE_URL);
        }

        @Test
        @DisplayName("returns presigned URL when caller is the contract owner")
        void shouldReturn_url_forOwner() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            String url = contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("returns presigned URL when caller is a reviewer on the contract")
        void shouldReturn_url_forReviewer() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            String url = contractService.generatePresignedViewUrl(CONTRACT_ID, REVIEWER);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("returns presigned URL when caller is the approver on the contract")
        void shouldReturn_url_forApprover() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            String url = contractService.generatePresignedViewUrl(CONTRACT_ID, APPROVER);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("returns presigned URL when caller is an internal signer on the contract")
        void shouldReturn_url_forInternalSigner() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            String url = contractService.generatePresignedViewUrl(CONTRACT_ID, INT_SIGNER);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("calls minioClient.getPresignedObjectUrl exactly once when access is granted")
        void shouldCallMinio_exactlyOnce_whenAccessGranted() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER);

            verify(minioClient, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("throws NotFoundException when the contract ID does not exist")
        void shouldThrow_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when caller has no role on the contract")
        void shouldThrow_404_whenCallerHasNoRole() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            assertThrows(NotFoundException.class,
                    () -> contractService.generatePresignedViewUrl(CONTRACT_ID, STRANGER));
        }

        @Test
        @DisplayName("does not call MinIO when caller has no role")
        void shouldNotCallMinio_whenCallerHasNoRole() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            assertThrows(NotFoundException.class,
                    () -> contractService.generatePresignedViewUrl(CONTRACT_ID, STRANGER));

            verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("throws BadRequestException when the PDF has not been uploaded yet")
        void shouldThrow_400_whenFileNotUploaded() {
            Contract c = uploadedContract();
            c.setFileUploaded(false);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER));
        }

        @Test
        @DisplayName("does not call MinIO when the file has not been uploaded yet")
        void shouldNotCallMinio_whenFileNotUploaded() throws Exception {
            Contract c = uploadedContract();
            c.setFileUploaded(false);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER));

            verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("uses the signed PDF key (_signed.pdf) when it exists in MinIO")
        void shouldUse_signedPdfKey_whenSignedExists() throws Exception {
            // statObject does not throw by default → objectExistsInMinio returns true
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER);

            // statObject was checked for the _signed.pdf key
            verify(minioClient, atLeastOnce()).statObject(any(StatObjectArgs.class));
        }

        @Test
        @DisplayName("falls back to original PDF key when signed PDF does not exist in MinIO")
        void shouldFallback_toOriginalKey_whenSignedMissing() throws Exception {
            // Make statObject throw → objectExistsInMinio returns false → uses original key
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));
            doThrow(new RuntimeException("NoSuchKey")).when(minioClient).statObject(any(StatObjectArgs.class));

            // Should still return a URL (using the original key now)
            String url = contractService.generatePresignedViewUrl(CONTRACT_ID, OWNER);

            assertEquals(FAKE_URL, url);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generatePresignedPartUrl — PUT URL for uploading a single chunk
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generatePresignedPartUrl")
    class GeneratePresignedPartUrl {

        @BeforeEach
        void stubMinio() throws Exception {
            lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                    .thenReturn(FAKE_URL);
        }

        @Test
        @DisplayName("returns presigned upload URL when owner requests it")
        void shouldReturn_uploadUrl_forOwner() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            String url = contractService.generatePresignedPartUrl(CONTRACT_ID, "upload-id-1", 1, OWNER);

            assertEquals(FAKE_URL, url);
        }

        @Test
        @DisplayName("calls minioClient.getPresignedObjectUrl exactly once for a valid request")
        void shouldCallMinio_exactlyOnce() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            contractService.generatePresignedPartUrl(CONTRACT_ID, "upload-id-1", 1, OWNER);

            verify(minioClient, times(1)).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }

        @Test
        @DisplayName("throws NotFoundException when the contract does not exist")
        void shouldThrow_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> contractService.generatePresignedPartUrl(CONTRACT_ID, "upload-id-1", 1, OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when the caller is not the contract owner")
        void shouldThrow_404_whenCallerIsNotOwner() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            assertThrows(NotFoundException.class,
                    () -> contractService.generatePresignedPartUrl(CONTRACT_ID, "upload-id-1", 1, STRANGER));
        }

        @Test
        @DisplayName("does not call MinIO when the caller is not the owner")
        void shouldNotCallMinio_whenCallerIsNotOwner() throws Exception {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(uploadedContract()));

            assertThrows(NotFoundException.class,
                    () -> contractService.generatePresignedPartUrl(CONTRACT_ID, "upload-id-1", 1, STRANGER));

            verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        }
    }
}
