package com.costacloud.contractmanagement.scheduler;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.Template;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.service.ContractRenewalService;
import com.costacloud.contractmanagement.service.ContractService;
import com.costacloud.contractmanagement.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class UploadCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupScheduler.class);

    private final TemplateService templateService;
    private final ContractService contractService;
    private final ContractRenewalService contractRenewalService;
    private final ContractRepository contractRepository;
    private final CustomMinioClient customMinioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public UploadCleanupScheduler(TemplateService templateService,
                                  ContractService contractService,
                                  ContractRenewalService contractRenewalService,
                                  ContractRepository contractRepository,
                                  CustomMinioClient customMinioClient) {
        this.templateService = templateService;
        this.contractService = contractService;
        this.contractRenewalService = contractRenewalService;
        this.contractRepository = contractRepository;
        this.customMinioClient = customMinioClient;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanOrphanedUploads() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

        // ── Templates: abort MinIO upload + delete record ─────────
        List<Template> orphanedTemplates = templateService.findOrphanedUploads(cutoff);
        for (Template template : orphanedTemplates) {
            try {
                if (template.getUploadId() != null) {
                    customMinioClient.cancelMultipartUpload(
                            bucketName,
                            "templates/" + template.getId() + ".pdf",
                            template.getUploadId()
                    );
                }
                templateService.deleteTemplate(template.getId());
                log.info("Cleaned orphaned template upload: {}", template.getId());
            } catch (Exception e) {
                log.warn("Failed to clean template {}: {}", template.getId(), e.getMessage());
            }
        }

        // ── Contracts: abort MinIO upload only — keep metadata ────
        List<Contract> orphanedContracts = contractService.findOrphanedUploads(cutoff);
        for (Contract contract : orphanedContracts) {
            try {
                if (contract.getUploadId() != null) {
                    customMinioClient.cancelMultipartUpload(
                            bucketName,
                            "contracts/" + contract.getId() + ".pdf",
                            contract.getUploadId()
                    );
                }
                log.info("Cleaned orphaned contract upload: {}", contract.getId());
            } catch (Exception e) {
                log.warn("Failed to clean contract {}: {}", contract.getId(), e.getMessage());
            }
        }

        // ── Renewal drafts: abort any in-progress upload + delete the document ─
        // A renewal draft is orphaned when the user started a renewal (renewedFromId set)
        // but never uploaded the PDF — the original contract remains untouched so
        // the Renew button reappears naturally.
        List<Contract> orphanedRenewals = contractRenewalService.findOrphanedRenewalDrafts(cutoff);
        for (Contract renewal : orphanedRenewals) {
            try {
                if (renewal.getUploadId() != null) {
                    customMinioClient.cancelMultipartUpload(
                            bucketName,
                            "contracts/" + renewal.getId() + ".pdf",
                            renewal.getUploadId()
                    );
                }
                contractRepository.deleteById(renewal.getId());
                log.info("Cleaned orphaned renewal draft: {}", renewal.getId());
            } catch (Exception e) {
                log.warn("Failed to clean renewal draft {}: {}", renewal.getId(), e.getMessage());
            }
        }
    }
}
