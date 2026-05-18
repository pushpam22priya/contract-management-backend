package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.model.FileRecord;
import com.costacloud.contractmanagement.repository.FileRecordRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/test")
public class TestUploadController {

    private final MinioClient minioClient;
    private final FileRecordRepository fileRecordRepository;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public TestUploadController(MinioClient minioClient, FileRecordRepository fileRecordRepository) {
        this.minioClient = minioClient;
        this.fileRecordRepository = fileRecordRepository;
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 1. Upload to MinIO
            String fileKey = "test/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileKey)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            response.put("minioStatus", "SUCCESS");
            response.put("fileKey", fileKey);

            // 2. Save to MongoDB
            FileRecord record = new FileRecord();
            record.setFileName(file.getOriginalFilename());
            record.setFileKey(fileKey);
            record.setContentType(file.getContentType());
            record.setFileSize(file.getSize());
            FileRecord saved = fileRecordRepository.save(record);
            response.put("mongoStatus", "SUCCESS");
            response.put("mongoId", saved.getId());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
