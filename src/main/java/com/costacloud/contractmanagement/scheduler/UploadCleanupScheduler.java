package com.costacloud.contractmanagement.scheduler;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.model.Template;
import com.costacloud.contractmanagement.repository.TemplateRepository;
import com.costacloud.contractmanagement.service.TemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class UploadCleanupScheduler {

    private final TemplateService templateService;
    private final TemplateRepository templateRepository;
    private final CustomMinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public UploadCleanupScheduler(TemplateService templateService,
                                  TemplateRepository templateRepository,
                                  CustomMinioClient minioClient) {
        this.templateService = templateService;
        this.templateRepository = templateRepository;
        this.minioClient = minioClient;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOrphanedUploads() throws Exception {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<Template> orphaned = templateService.findOrphanedUploads(cutoff);

        for (Template template : orphaned) {
            if (template.getUploadId() != null) {
                minioClient.cancelMultipartUpload(
                        bucketName,
                        "templates/" + template.getId() + ".pdf",
                        template.getUploadId()
                );
            }
            templateRepository.deleteById(template.getId());
        }
    }
}
