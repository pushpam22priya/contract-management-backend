package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.ContractRequest;
import com.costacloud.contractmanagement.dto.ContractResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.repository.ContractRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractService - mergeFormFieldsSafe")
class ContractServiceMergeFieldsTest {

    @Mock ContractRepository contractRepository;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks ContractService contractService;

    private static final String CONTRACT_ID = "contract-merge-01";
    private static final String OWNER_EMAIL = "owner@test.com";

    @BeforeEach
    void stubSave() {
        lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(contractService, "bucketName", "test-bucket");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Contract in DRAFT status — allowed to be updated */
    private Contract draftContract(List<Map<String, Object>> formFields) {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER_EMAIL);
        c.setStatus(ContractStatus.DRAFT);
        c.setTitle("My Contract");
        c.setClient("Client Co");
        c.setFormFields(formFields);
        return c;
    }

    /** A field map with all 5 protected keys set */
    private Map<String, Object> storedField(String name) {
        Map<String, Object> f = new HashMap<>();
        f.put("fieldName", name);
        f.put("assignedParty", "party_1");
        f.put("partyLabel", "Buyer");
        f.put("partyColor", "#ff0000");
        f.put("profileKey", "buyer_profile");
        f.put("lockedBy", "party_1");
        return f;
    }

    /** What the frontend sends back after Apryse strips the protected keys */
    private Map<String, Object> incomingField(String name) {
        Map<String, Object> f = new HashMap<>();
        f.put("fieldName", name);
        f.put("value", "signed");
        // no assignedParty, partyLabel, partyColor, profileKey, lockedBy
        return f;
    }

    private ContractRequest requestWithFormFields(List<Map<String, Object>> fields) {
        ContractRequest r = new ContractRequest();
        r.setFormFields(fields);
        return r;
    }

    private List<Map<String, Object>> getFormFieldsAfterUpdate(List<Map<String, Object>> stored,
                                                                List<Map<String, Object>> incoming) {
        when(contractRepository.findById(CONTRACT_ID))
                .thenReturn(Optional.of(draftContract(stored)));
        ContractResponse response = contractService.updateContract(
                CONTRACT_ID, requestWithFormFields(incoming), OWNER_EMAIL);
        // Capture the contract that was saved
        org.mockito.ArgumentCaptor<Contract> captor =
                org.mockito.ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue().getFormFields();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Protected fields preservation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preserves assignedParty from stored field when incoming strips it")
    void shouldPreserve_assignedParty() {
        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(storedField("sig_1")),
            List.of(incomingField("sig_1"))  // no assignedParty
        );

        assertEquals("party_1", result.get(0).get("assignedParty"));
    }

    @Test
    @DisplayName("preserves partyLabel from stored field")
    void shouldPreserve_partyLabel() {
        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(storedField("sig_1")),
            List.of(incomingField("sig_1"))
        );

        assertEquals("Buyer", result.get(0).get("partyLabel"));
    }

    @Test
    @DisplayName("preserves partyColor from stored field")
    void shouldPreserve_partyColor() {
        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(storedField("sig_1")),
            List.of(incomingField("sig_1"))
        );

        assertEquals("#ff0000", result.get(0).get("partyColor"));
    }

    @Test
    @DisplayName("preserves profileKey and lockedBy from stored field")
    void shouldPreserve_profileKey_andLockedBy() {
        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(storedField("sig_1")),
            List.of(incomingField("sig_1"))
        );

        assertEquals("buyer_profile", result.get(0).get("profileKey"));
        assertEquals("party_1",       result.get(0).get("lockedBy"));
    }

    @Test
    @DisplayName("non-protected fields (like value) are updated from incoming")
    void shouldUpdate_nonProtectedFields() {
        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(storedField("sig_1")),
            List.of(incomingField("sig_1"))  // has value="signed"
        );

        assertEquals("signed", result.get(0).get("value"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sets formFields directly when contract has no existing fields (first save)")
    void shouldSetFormFields_directly_whenContractHasNone() {
        List<Map<String, Object>> incoming = List.of(incomingField("sig_1"));

        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            null,       // no existing formFields
            incoming
        );

        // No existing fields to merge with — just stores incoming as-is
        assertEquals(1, result.size());
        assertEquals("sig_1", result.get(0).get("fieldName"));
    }

    @Test
    @DisplayName("adds incoming field as-is when it has no matching stored field")
    void shouldAddIncomingField_whenNoStoredMatch() {
        // stored has sig_1, incoming adds a brand-new sig_2
        Map<String, Object> newField = new HashMap<>();
        newField.put("fieldName", "sig_2");
        newField.put("value", "new_value");

        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(storedField("sig_1")),
            List.of(incomingField("sig_1"), newField)
        );

        assertEquals(2, result.size());
        // sig_2 has no stored counterpart — stored as-is
        Map<String, Object> sig2 = result.stream()
                .filter(f -> "sig_2".equals(f.get("fieldName"))).findFirst().orElseThrow();
        assertNull(sig2.get("assignedParty"));
    }

    @Test
    @DisplayName("works with 'name' key variant in addition to 'fieldName'")
    void shouldWork_withNameKeyVariant() {
        // Apryse sometimes uses "name" instead of "fieldName"
        Map<String, Object> stored = new HashMap<>();
        stored.put("name", "sig_1");
        stored.put("assignedParty", "party_2");

        Map<String, Object> incoming = new HashMap<>();
        incoming.put("name", "sig_1");
        incoming.put("value", "done");

        List<Map<String, Object>> result = getFormFieldsAfterUpdate(
            List.of(stored),
            List.of(incoming)
        );

        assertEquals("party_2", result.get(0).get("assignedParty"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status guard — formFields update blocked while in review/approval
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("throws BadRequestException when contract is in review (cannot be edited)")
    void shouldThrow_whenContractInReview() {
        Contract c = draftContract(List.of());
        c.setStatus(ContractStatus.IN_REVIEW);
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

        assertThrows(BadRequestException.class, () ->
            contractService.updateContract(
                CONTRACT_ID,
                requestWithFormFields(List.of(incomingField("sig_1"))),
                OWNER_EMAIL
            )
        );
    }
}
