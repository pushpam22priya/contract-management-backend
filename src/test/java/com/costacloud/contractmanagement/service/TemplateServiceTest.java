package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.ChunkCompleteRequest;
import com.costacloud.contractmanagement.dto.ChunkUploadInitResponse;
import com.costacloud.contractmanagement.dto.TemplateRequest;
import com.costacloud.contractmanagement.dto.TemplateResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.model.Template;
import com.costacloud.contractmanagement.repository.TemplateRepository;
import io.minio.*;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateService")
class TemplateServiceTest {

    @Mock TemplateRepository templateRepository;
    @Mock MinioClient       minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate     mongoTemplate;

    @InjectMocks TemplateService templateService;

    private static final String TEMPLATE_ID = "template-001";
    private static final String USER_EMAIL  = "admin@test.com";
    private static final String BUCKET      = "test-bucket";

    @BeforeEach
    void injectBucket() {
        ReflectionTestUtils.setField(templateService, "bucketName", BUCKET);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Template savedTemplate() {
        Template t = new Template();
        t.setId(TEMPLATE_ID);
        t.setName("NDA Template");
        t.setCategory("Legal");
        t.setDescription("Standard NDA");
        t.setFileUploaded(true);
        t.setFileUrl("templates/" + TEMPLATE_ID + ".pdf");
        t.setTimesUsed(0);
        return t;
    }

    private TemplateRequest request(String name) {
        TemplateRequest r = new TemplateRequest();
        r.setName(name);
        r.setCategory("Legal");
        r.setDescription("Standard NDA");
        return r;
    }

    private Template.FormField fieldWith(String name, String assignedParty) {
        Template.FormField f = new Template.FormField();
        f.setName(name);
        f.setLabel("Full Name");
        f.setAssignedParty(assignedParty);
        return f;
    }

    private ChunkCompleteRequest chunkCompleteRequest(String uploadId) {
        ChunkCompleteRequest req = new ChunkCompleteRequest();
        req.setUploadId(uploadId);
        ChunkCompleteRequest.Part part = new ChunkCompleteRequest.Part();
        part.setPartNumber(1);
        part.setETag("\"abc123\"");
        req.setParts(List.of(part));
        return req;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE TEMPLATE
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("throws BadRequestException when name already exists")
        void shouldThrow_whenNameAlreadyExists() {
            when(templateRepository.existsByName("NDA Template")).thenReturn(true);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                templateService.createTemplate(request("NDA Template"), USER_EMAIL)
            );
            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("saves template to repository on success")
        void shouldSave_templateToRepository() {
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> {
                Template t = i.getArgument(0);
                t.setId(TEMPLATE_ID);
                return t;
            });

            templateService.createTemplate(request("NDA Template"), USER_EMAIL);

            verify(templateRepository, times(1)).save(any(Template.class));
        }

        @Test
        @DisplayName("returns the new template ID")
        void shouldReturnTemplateId() {
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> {
                Template t = i.getArgument(0);
                t.setId(TEMPLATE_ID);
                return t;
            });

            String id = templateService.createTemplate(request("NDA Template"), USER_EMAIL);

            assertEquals(TEMPLATE_ID, id);
        }

        @Test
        @DisplayName("sets fileUploaded = false and timesUsed = 0 on new template")
        void shouldSetDefaults_onNewTemplate() {
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.createTemplate(request("NDA Template"), USER_EMAIL);

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertFalse(captor.getValue().isFileUploaded());
            assertEquals(0, captor.getValue().getTimesUsed());
        }

        @Test
        @DisplayName("stores the uploadedBy email on the template")
        void shouldSetUploadedBy() {
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.createTemplate(request("NDA Template"), USER_EMAIL);

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertEquals(USER_EMAIL, captor.getValue().getUploadedBy());
        }

        @Test
        @DisplayName("trims whitespace from the template name before saving")
        void shouldTrimName_beforeSaving() {
            when(templateRepository.existsByName("NDA Template")).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.createTemplate(request("  NDA Template  "), USER_EMAIL);

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertEquals("NDA Template", captor.getValue().getName());
        }

        @Test
        @DisplayName("sets hasFormFields = true when formFields list is non-empty")
        void shouldSetHasFormFields_true_whenFieldsPresent() {
            TemplateRequest req = request("Contract");
            req.setFormFields(List.of(fieldWith("FullName", "party_1")));
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.createTemplate(req, USER_EMAIL);

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertTrue(captor.getValue().isHasFormFields());
        }

        @Test
        @DisplayName("sets hasFormFields = false when formFields list is null")
        void shouldSetHasFormFields_false_whenFieldsNull() {
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.createTemplate(request("Contract"), USER_EMAIL);

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertFalse(captor.getValue().isHasFormFields());
        }

        @Test
        @DisplayName("throws BadRequestException when a form field has no assignedParty")
        void shouldThrow_whenFieldMissingAssignedParty() {
            TemplateRequest req = request("Contract");
            req.setFormFields(List.of(fieldWith("FullName", null)));
            when(templateRepository.existsByName(any())).thenReturn(false);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                templateService.createTemplate(req, USER_EMAIL)
            );
            assertTrue(ex.getMessage().contains("must be assigned to a party"));
        }

        @Test
        @DisplayName("does not throw when formFields list is null (no party validation needed)")
        void shouldNotThrow_whenFormFieldsNull() {
            when(templateRepository.existsByName(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertDoesNotThrow(() ->
                templateService.createTemplate(request("Contract"), USER_EMAIL)
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE TEMPLATE
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("throws RuntimeException when template not found")
        void shouldThrow_whenTemplateNotFound() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () ->
                templateService.updateTemplate(TEMPLATE_ID, request("New Name"))
            );
        }

        @Test
        @DisplayName("throws BadRequestException when name is used by another template")
        void shouldThrow_whenNameTakenByAnotherTemplate() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(templateRepository.existsByNameAndIdNot("NDA Template", TEMPLATE_ID)).thenReturn(true);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                templateService.updateTemplate(TEMPLATE_ID, request("NDA Template"))
            );
            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("does not throw when name belongs to the same template")
        void shouldNotThrow_whenNameBelongsToSameTemplate() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(templateRepository.existsByNameAndIdNot(any(), eq(TEMPLATE_ID))).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertDoesNotThrow(() ->
                templateService.updateTemplate(TEMPLATE_ID, request("NDA Template"))
            );
        }

        @Test
        @DisplayName("saves the updated template and returns TemplateResponse")
        void shouldSave_andReturnResponse() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(templateRepository.existsByNameAndIdNot(any(), any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            TemplateResponse resp = templateService.updateTemplate(TEMPLATE_ID, request("Updated Name"));

            verify(templateRepository).save(any(Template.class));
            assertNotNull(resp);
        }

        @Test
        @DisplayName("throws BadRequestException when an updated field has no assignedParty")
        void shouldThrow_whenUpdatedFieldMissingAssignedParty() {
            TemplateRequest req = request("NDA Template");
            req.setFormFields(List.of(fieldWith("SignatureDate", "")));
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(templateRepository.existsByNameAndIdNot(any(), any())).thenReturn(false);

            assertThrows(BadRequestException.class, () ->
                templateService.updateTemplate(TEMPLATE_ID, req)
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE TEMPLATE
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("throws RuntimeException when template not found")
        void shouldThrow_whenTemplateNotFound() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () ->
                templateService.deleteTemplate(TEMPLATE_ID)
            );
        }

        @Test
        @DisplayName("removes PDF from MinIO when fileUploaded is true")
        void shouldRemoveFromMinio_whenFileUploaded() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));

            templateService.deleteTemplate(TEMPLATE_ID);

            verify(minioClient, times(1)).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("does not touch MinIO when fileUploaded is false")
        void shouldSkipMinio_whenFileNotUploaded() throws Exception {
            Template t = savedTemplate();
            t.setFileUploaded(false);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(t));

            templateService.deleteTemplate(TEMPLATE_ID);

            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("deletes template from repository")
        void shouldDeleteFromRepository() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));

            templateService.deleteTemplate(TEMPLATE_ID);

            verify(templateRepository, times(1)).deleteById(TEMPLATE_ID);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Read operations")
    class ReadOperations {

        @Test
        @DisplayName("listTemplates returns a response per template in the repository")
        void shouldReturnOneResponse_perTemplate() {
            when(templateRepository.findAllExcludingLargeFields())
                .thenReturn(List.of(savedTemplate(), savedTemplate()));

            assertEquals(2, templateService.listTemplates().size());
        }

        @Test
        @DisplayName("listTemplates returns empty list when no templates exist")
        void shouldReturnEmptyList_whenNoTemplates() {
            when(templateRepository.findAllExcludingLargeFields()).thenReturn(List.of());

            assertTrue(templateService.listTemplates().isEmpty());
        }

        @Test
        @DisplayName("getTemplate throws RuntimeException when not found")
        void shouldThrow_whenTemplateNotFound() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () ->
                templateService.getTemplate(TEMPLATE_ID)
            );
        }

        @Test
        @DisplayName("getTemplate returns TemplateResponse for existing template")
        void shouldReturnResponse_forExistingTemplate() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));

            TemplateResponse resp = templateService.getTemplate(TEMPLATE_ID);

            assertNotNull(resp);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILE UPLOAD (single-shot)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("uploadFile")
    class FileUpload {

        @Test
        @DisplayName("throws RuntimeException when template not found")
        void shouldThrow_whenTemplateNotFound() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () ->
                templateService.uploadFile(TEMPLATE_ID, validPdfStream(), -1)
            );
        }

        @Test
        @DisplayName("throws RuntimeException when uploaded file is not a valid PDF")
        void shouldThrow_whenFileIsNotPdf() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));

            InputStream notPdf = new ByteArrayInputStream("NOTAPDF_CONTENT".getBytes());

            assertThrows(RuntimeException.class, () ->
                templateService.uploadFile(TEMPLATE_ID, notPdf, -1)
            );
        }

        @Test
        @DisplayName("calls minioClient.putObject with the correct object key")
        void shouldCallPutObject_withCorrectKey() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.uploadFile(TEMPLATE_ID, validPdfStream(), -1);

            verify(minioClient).putObject(argThat(args ->
                args.object().equals("templates/" + TEMPLATE_ID + ".pdf")
            ));
        }

        @Test
        @DisplayName("sets fileUploaded = true and saves template after upload")
        void shouldSetFileUploaded_andSave() throws Exception {
            Template t = savedTemplate();
            t.setFileUploaded(false);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(t));
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            templateService.uploadFile(TEMPLATE_ID, validPdfStream(), -1);

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertTrue(captor.getValue().isFileUploaded());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET FILE
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getFile")
    class GetFile {

        @Test
        @DisplayName("throws RuntimeException when template not found")
        void shouldThrow_whenTemplateNotFound() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () ->
                templateService.getFile(TEMPLATE_ID)
            );
        }

        @Test
        @DisplayName("throws RuntimeException when file has not been uploaded yet")
        void shouldThrow_whenFileNotUploaded() {
            Template t = savedTemplate();
            t.setFileUploaded(false);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(t));

            assertThrows(RuntimeException.class, () ->
                templateService.getFile(TEMPLATE_ID)
            );
        }

        @Test
        @DisplayName("calls minioClient.getObject with the correct key")
        void shouldCallGetObject_withCorrectKey() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            GetObjectResponse mockResp = mock(GetObjectResponse.class);
            when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResp);

            templateService.getFile(TEMPLATE_ID);

            verify(minioClient).getObject(argThat(args ->
                args.object().equals("templates/" + TEMPLATE_ID + ".pdf")
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHUNKED UPLOAD
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chunked upload")
    class ChunkedUpload {

        @Test
        @DisplayName("initiateChunkedUpload throws when template not found")
        void shouldThrow_whenTemplateNotFound_onInitiate() {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () ->
                templateService.initiateChunkedUpload(TEMPLATE_ID)
            );
        }

        @Test
        @DisplayName("initiateChunkedUpload returns uploadId from MinIO")
        void shouldReturnUploadId_fromMinio() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(customMinioClient.startMultipartUpload(eq(BUCKET), any())).thenReturn("upload-xyz");

            ChunkUploadInitResponse resp = templateService.initiateChunkedUpload(TEMPLATE_ID);

            assertEquals("upload-xyz", resp.getUploadId());
            assertEquals(TEMPLATE_ID,  resp.getTemplateId());
        }

        @Test
        @DisplayName("completeChunkedUpload calls finishMultipartUpload on MinIO")
        void shouldCallFinishMultipartUpload() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().doNothing().when(customMinioClient)
                .finishMultipartUpload(any(), any(), any(), any());

            templateService.completeChunkedUpload(TEMPLATE_ID, chunkCompleteRequest("up-001"));

            verify(customMinioClient).finishMultipartUpload(
                eq(BUCKET), eq("templates/" + TEMPLATE_ID + ".pdf"), eq("up-001"), any()
            );
        }

        @Test
        @DisplayName("completeChunkedUpload sets fileUploaded = true and clears uploadId")
        void shouldSetFileUploaded_andClearUploadId_onComplete() throws Exception {
            Template t = savedTemplate();
            t.setFileUploaded(false);
            t.setUploadId("up-001");
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(t));
            when(templateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().doNothing().when(customMinioClient)
                .finishMultipartUpload(any(), any(), any(), any());

            templateService.completeChunkedUpload(TEMPLATE_ID, chunkCompleteRequest("up-001"));

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(templateRepository).save(captor.capture());
            assertTrue(captor.getValue().isFileUploaded());
            assertNull(captor.getValue().getUploadId());
        }

        @Test
        @DisplayName("abortChunkedUpload calls cancelMultipartUpload on MinIO")
        void shouldCallCancelMultipartUpload() throws Exception {
            lenient().doNothing().when(customMinioClient)
                .cancelMultipartUpload(any(), any(), any());

            templateService.abortChunkedUpload(TEMPLATE_ID, "up-001");

            verify(customMinioClient).cancelMultipartUpload(
                eq(BUCKET), eq("templates/" + TEMPLATE_ID + ".pdf"), eq("up-001")
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRESIGNED VIEW URL
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generatePresignedViewUrl")
    class PresignedViewUrl {

        @Test
        @DisplayName("throws RuntimeException when file has not been uploaded yet")
        void shouldThrow_whenFileNotUploaded() {
            Template t = savedTemplate();
            t.setFileUploaded(false);
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(t));

            assertThrows(RuntimeException.class, () ->
                templateService.generatePresignedViewUrl(TEMPLATE_ID)
            );
        }

        @Test
        @DisplayName("returns presigned URL from MinIO when file is uploaded")
        void shouldReturnPresignedUrl() throws Exception {
            when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(savedTemplate()));
            when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://minio/presigned-url");

            String url = templateService.generatePresignedViewUrl(TEMPLATE_ID);

            assertEquals("http://minio/presigned-url", url);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INCREMENT TIMES USED
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("incrementTimesUsed")
    class IncrementTimesUsed {

        @Test
        @DisplayName("calls mongoTemplate.updateFirst to increment timesUsed")
        void shouldCallMongoTemplate_toIncrement() {
            templateService.incrementTimesUsed(TEMPLATE_ID);

            verify(mongoTemplate, times(1)).updateFirst(any(), any(), eq(Template.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper — valid PDF InputStream
    // ─────────────────────────────────────────────────────────────────────────

    private InputStream validPdfStream() {
        // %PDF header followed by padding bytes
        byte[] bytes = new byte[]{37, 80, 68, 70, 45, 49, 46, 52}; // %PDF-1.4
        return new ByteArrayInputStream(bytes);
    }
}
