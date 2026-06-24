package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.FolderRequest;
import com.costacloud.contractmanagement.dto.FolderResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Folder;
import com.costacloud.contractmanagement.repository.FolderRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final MongoTemplate mongoTemplate;

    public FolderService(FolderRepository folderRepository, MongoTemplate mongoTemplate) {
        this.folderRepository = folderRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public FolderResponse createFolder(FolderRequest request, String email) {
        String name = request.getName().trim();
        if (folderRepository.existsByNameAndCreatedBy(name, email)) {
            throw new BadRequestException("A folder with this name already exists");
        }
        Folder folder = new Folder();
        folder.setName(name);
        folder.setCreatedBy(email);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        folderRepository.save(folder);
        return new FolderResponse(folder);
    }

    public List<FolderResponse> listFolders(String email) {
        return folderRepository.findByCreatedByOrderByCreatedAtDesc(email)
                .stream()
                .map(FolderResponse::new)
                .collect(Collectors.toList());
    }

    public FolderResponse renameFolder(String id, FolderRequest request, String email) {
        Folder folder = findByIdAndOwner(id, email);
        String name = request.getName().trim();
        if (folderRepository.existsByNameAndCreatedByAndIdNot(name, email, id)) {
            throw new BadRequestException("A folder with this name already exists");
        }
        folder.setName(name);
        folder.setUpdatedAt(LocalDateTime.now());
        folderRepository.save(folder);
        return new FolderResponse(folder);
    }

    public void deleteFolder(String id, String email) {
        findByIdAndOwner(id, email);
        long contractCount = mongoTemplate.count(
                Query.query(Criteria.where("folderId").is(id)),
                "contracts"
        );
        if (contractCount > 0) {
            throw new BadRequestException(
                    "Cannot delete: this folder contains " + contractCount + " contract(s)"
            );
        }
        folderRepository.deleteById(id);
    }

    // ─── Private Helpers ─────────────────────────────────────────

    private Folder findByIdAndOwner(String id, String email) {
        Folder folder = folderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Folder not found"));
        if (!folder.getCreatedBy().equals(email)) {
            throw new NotFoundException("Folder not found");
        }
        return folder;
    }
}
