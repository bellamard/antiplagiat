package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.DocumentResponseDTO;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt");

    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;
    private final Path storageDirectory;

    public DocumentService(
            DocumentsRespository documentsRespository,
            UsersRepository usersRepository,
            @Value("${app.documents.storage-dir:uploads/documents}") String storageDirectory
    ) {
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
        this.storageDirectory = Paths.get(storageDirectory).toAbsolutePath().normalize();
    }

    public DocumentResponseDTO uploadDocument(
            String username,
            MultipartFile file,
            String name,
            String faculty,
            String department,
            String author,
            String director,
            String rapporteur,
            String yearOfAcademic,
            String academic,
            String matriculation
    ) throws IOException {
        Users user = findUser(username);
        validateRequired(name, "Le nom du document est obligatoire");
        validateRequired(faculty, "La faculté est obligatoire");
        validateRequired(department, "Le département est obligatoire");
        validateRequired(author, "L'auteur est obligatoire");
        validateRequired(yearOfAcademic, "L'année académique est obligatoire");
        validateRequired(matriculation, "Le matricule est obligatoire");
        validateFile(file);

        if (documentsRespository.existsByMatriculation(matriculation)) {
            throw new IllegalArgumentException("Un document existe déjà avec ce matricule");
        }

        Files.createDirectories(storageDirectory);

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);
        String storedFileName = UUID.randomUUID() + "." + extension;
        Path destination = storageDirectory.resolve(storedFileName).normalize();

        if (!destination.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("Nom de fichier invalide");
        }

        Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

        Document document = Document.builder()
                .name(name)
                .faculty(faculty)
                .department(department)
                .author(author)
                .director(director)
                .rapporteur(rapporteur)
                .yearOfAcademic(yearOfAcademic)
                .academic(academic)
                .matriculation(matriculation)
                .user(user)
                .urlFile("/api/documents/pending/download")
                .storedFileName(storedFileName)
                .originalFileName(originalFileName)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .build();

        Document savedDocument = documentsRespository.save(document);
        savedDocument.setUrlFile("/api/documents/" + savedDocument.getId() + "/download");

        return toResponse(documentsRespository.save(savedDocument));
    }

    public List<DocumentResponseDTO> getDocuments(String username) {
        if (isCurrentUserAdmin()) {
            return documentsRespository.findAll()
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        Users user = findUser(username);
        return documentsRespository.findByUser(user)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public DocumentResponseDTO getDocumentById(UUID id, String username) {
        Document document = findDocument(id);
        assertCanAccess(document, username);
        return toResponse(document);
    }

    public Resource downloadDocument(UUID id, String username) {
        Document document = findDocument(id);
        assertCanAccess(document, username);
        Path filePath = storageDirectory.resolve(document.getStoredFileName()).normalize();

        if (!filePath.startsWith(storageDirectory) || !Files.exists(filePath)) {
            throw new EntityNotFoundException("Fichier introuvable");
        }

        try {
            return new UrlResource(filePath.toUri());
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Chemin de fichier invalide");
        }
    }

    public Document getDocumentEntity(UUID id, String username) {
        Document document = findDocument(id);
        assertCanAccess(document, username);
        return document;
    }

    public void deleteDocument(UUID id, String username) throws IOException {
        Document document = findDocument(id);
        assertCanAccess(document, username);
        Path filePath = storageDirectory.resolve(document.getStoredFileName()).normalize();
        documentsRespository.delete(document);

        if (filePath.startsWith(storageDirectory)) {
            Files.deleteIfExists(filePath);
        }
    }

    private Users findUser(String username) {
        return usersRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
    }

    private Document findDocument(UUID id) {
        return documentsRespository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable"));
    }

    private void assertCanAccess(Document document, String username) {
        if (isCurrentUserAdmin() || document.getUser().getUsername().equals(username)) {
            return;
        }

        throw new SecurityException("Accès refusé à ce document");
    }

    private boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        return authentication != null
                && authentication.getAuthorities()
                .stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private void validateRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Type de fichier non autorisé. Formats acceptés : pdf, doc, docx, txt");
        }
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("Nom de fichier invalide");
        }

        return Paths.get(originalFileName).getFileName().toString();
    }

    private String getExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf('.');

        if (extensionIndex < 0 || extensionIndex == fileName.length() - 1) {
            throw new IllegalArgumentException("Extension de fichier obligatoire");
        }

        return fileName.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
    }

    private DocumentResponseDTO toResponse(Document document) {
        return new DocumentResponseDTO(
                document.getId(),
                document.getName(),
                document.getFaculty(),
                document.getDepartment(),
                document.getAuthor(),
                document.getDirector(),
                document.getRapporteur(),
                document.getYearOfAcademic(),
                document.getAcademic(),
                document.getMatriculation(),
                document.getCreationDate(),
                document.getUser().getId(),
                document.getUrlFile(),
                document.getOriginalFileName(),
                document.getContentType(),
                document.getFileSize()
        );
    }
}
