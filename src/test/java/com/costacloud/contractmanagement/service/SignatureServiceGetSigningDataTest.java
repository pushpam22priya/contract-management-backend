package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.SignatureRequestResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.SignatureRequest;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureService - getSigningData")
class SignatureServiceGetSigningDataTest {

    @Mock ContractRepository contractRepository;
    @Mock SignatureRequestRepository signatureRequestRepository;
    @Mock AutoAdvanceService autoAdvanceService;
    @Mock EmailService emailService;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks SignatureService signatureService;

    private static final String TOKEN       = "tok-abc-123";
    private static final String CONTRACT_ID = "contract-456";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private SignatureRequest activeRequest() {
        SignatureRequest sr = new SignatureRequest();
        sr.setToken(TOKEN);
        sr.setContractId(CONTRACT_ID);
        sr.setContractTitle("Service Agreement");
        sr.setSignerEmail("signer@client.com");
        sr.setSignerName("Alice");
        sr.setStatus("pending");
        sr.setExpiresAt(LocalDateTime.now().plusDays(7));
        sr.setAssignedParty(List.of("party_1"));
        sr.setAssignedPartyLabel(List.of("Buyer"));
        sr.setOrder(1);
        sr.setContractVersion(1);
        return sr;
    }

    private Contract storedContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setVersion(1);
        c.setFormFields(List.of(Map.of("fieldName", "sig_1", "assignedParty", "party_1")));
        c.setXfdfData("<xfdf/>");
        return c;
    }

    @BeforeEach
    void stubDefaults() {
        // most tests need both stubs — override individually when testing edge cases
        lenient().when(signatureRequestRepository.findByToken(TOKEN))
                .thenReturn(Optional.of(activeRequest()));
        lenient().when(contractRepository.findById(CONTRACT_ID))
                .thenReturn(Optional.of(storedContract()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("throws NotFoundException when token does not exist")
    void shouldThrow_whenTokenNotFound() {
        when(signatureRequestRepository.findByToken("bad-token"))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () ->
                signatureService.getSigningData("bad-token")
        );
    }

    @Test
    @DisplayName("throws BadRequestException when signer has already signed")
    void shouldThrow_whenAlreadySigned() {
        SignatureRequest sr = activeRequest();
        sr.setStatus("signed");
        when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.getSigningData(TOKEN)
        );
        assertEquals("You have already submitted your signature", ex.getMessage());
    }

    @Test
    @DisplayName("throws BadRequestException when signing link has expired")
    void shouldThrow_whenTokenExpired() {
        SignatureRequest sr = activeRequest();
        sr.setExpiresAt(LocalDateTime.now().minusDays(1));  // expired yesterday
        when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.getSigningData(TOKEN)
        );
        assertTrue(ex.getMessage().contains("signing link expired"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // assignedParty type — the bug that broke frontend party restriction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns assignedParty as a single String, not a List")
    void shouldReturn_assignedPartyAsString_notList() {
        // stored as List["party_1"] in MongoDB, must arrive as "party_1" to frontend
        SignatureRequestResponse response = signatureService.getSigningData(TOKEN);

        assertEquals("party_1", response.getAssignedParty());
        // the field type in the response DTO is String, not List<String>
        assertInstanceOf(String.class, response.getAssignedParty());
    }

    @Test
    @DisplayName("returns null assignedParty when the stored list is empty")
    void shouldReturn_null_whenAssignedPartyListIsEmpty() {
        SignatureRequest sr = activeRequest();
        sr.setAssignedParty(List.of());  // empty list
        when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

        SignatureRequestResponse response = signatureService.getSigningData(TOKEN);

        assertNull(response.getAssignedParty());
    }

    @Test
    @DisplayName("returns null assignedParty when the stored list is null")
    void shouldReturn_null_whenAssignedPartyIsNull() {
        SignatureRequest sr = activeRequest();
        sr.setAssignedParty(null);
        when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));

        SignatureRequestResponse response = signatureService.getSigningData(TOKEN);

        assertNull(response.getAssignedParty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Form fields — must come from the live contract, not the SignatureRequest snapshot
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("formFields in response come from the live contract, not the SignatureRequest")
    void shouldReturn_formFieldsFromContract() {
        // Contract has updated formFields; SignatureRequest snapshot is old
        Contract c = storedContract();
        c.setFormFields(List.of(Map.of("fieldName", "sig_1", "assignedParty", "party_1")));

        SignatureRequest sr = activeRequest();
        sr.setFormFields(List.of(Map.of("fieldName", "sig_1")));  // stripped — no assignedParty

        when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

        SignatureRequestResponse response = signatureService.getSigningData(TOKEN);

        // Must reflect the contract's authoritative copy, not the snapshot
        assertEquals("party_1", response.getFormFields().get(0).get("assignedParty"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract version sync
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saves updated contractVersion on SignatureRequest when versions differ")
    void shouldSave_whenContractVersionMismatch() {
        SignatureRequest sr = activeRequest();
        sr.setContractVersion(1);

        Contract c = storedContract();
        c.setVersion(2);  // contract was updated after this SR was created

        when(signatureRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(sr));
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

        signatureService.getSigningData(TOKEN);

        // Should persist the updated version number on the SR
        verify(signatureRequestRepository, times(1)).save(sr);
        assertEquals(2, sr.getContractVersion());
    }

    @Test
    @DisplayName("does NOT save SignatureRequest when contract version matches")
    void shouldNotSave_whenVersionMatches() {
        // sr.contractVersion == contract.version — no save needed
        signatureService.getSigningData(TOKEN);

        verify(signatureRequestRepository, never()).save(any());
    }
}
