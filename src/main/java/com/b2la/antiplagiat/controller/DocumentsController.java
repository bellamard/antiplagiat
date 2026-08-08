package com.b2la.antiplagiat.controller;

import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.dto.DocumentResponseDTO;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.service.DocumentService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentsController {

    private final DocumentService documentService;

    public DocumentsController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentResponseDTO> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam String faculty,
            @RequestParam String department,
            @RequestParam String author,
            @RequestParam(required = false) String director,
            @RequestParam(required = false) String rapporteur,
            @RequestParam String yearOfAcademic,
            @RequestParam(required = false) String academic,
            @RequestParam String matriculation,
            Authentication authentication
    ) throws IOException {
        DocumentResponseDTO document = documentService.uploadDocument(
                authentication.getName(),
                file,
                name,
                faculty,
                department,
                author,
                director,
                rapporteur,
                yearOfAcademic,
                academic,
                matriculation
        );

        return new ApiResponse<>("success", "Document uploadé", document);
    }

    @GetMapping
    public ApiResponse<List<DocumentResponseDTO>> getDocuments(Authentication authentication) {
        return new ApiResponse<>(
                "success",
                documentService.getDocuments(authentication.getName())
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponseDTO> getDocumentById(@PathVariable UUID id, Authentication authentication) {
        return new ApiResponse<>(
                "success",
                documentService.getDocumentById(id, authentication.getName())
        );
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable UUID id, Authentication authentication) {
        Document document = documentService.getDocumentEntity(id, authentication.getName());
        Resource resource = documentService.downloadDocument(id, authentication.getName());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resolveContentType(document)))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(document.getOriginalFileName())
                                .build()
                                .toString()
                )
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable UUID id, Authentication authentication) throws IOException {
        documentService.deleteDocument(id, authentication.getName());
        return ResponseEntity.ok(new ApiResponse<>("success", "Document supprimé"));
    }

    private String resolveContentType(Document document) {
        if (document.getContentType() == null || document.getContentType().isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return document.getContentType();
    }
}
