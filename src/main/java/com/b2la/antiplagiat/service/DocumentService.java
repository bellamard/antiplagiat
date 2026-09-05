package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.DocumentResponseDTO;
import com.b2la.antiplagiat.analysis.application.AnalysisService;
import com.b2la.antiplagiat.analysis.application.AnalysisView;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
@Transactional
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "txt",
            "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp"
    );
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "image/png",
            "image/jpeg",
            "image/tiff",
            "image/bmp",
            "image/gif",
            "image/webp"
    );
    private static final String PENDING_DOCUMENT_URL_PREFIX = "/api/documents/pending";

    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;
    private final AnalysisService analysisService;
    private final Tika tika = new Tika();
    private final Path storageDirectory;
    private final long maxDatabaseBase64FileSize;

    public DocumentService(
            DocumentsRespository documentsRespository,
            UsersRepository usersRepository,
            AnalysisService analysisService,
            @Value("${app.documents.storage-dir:uploads/documents}") String storageDirectory,
            @Value("${app.documents.database-content-max-size-bytes:0}") long maxDatabaseBase64FileSize
    ) {
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
        this.analysisService = analysisService;
        this.storageDirectory = Paths.get(storageDirectory).toAbsolutePath().normalize();
        this.maxDatabaseBase64FileSize = maxDatabaseBase64FileSize;
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
        String detectedContentType = validateFile(file);

        if (documentsRespository.existsByMatriculation(matriculation)) {
            throw new IllegalArgumentException("Un document existe déjà avec ce matricule");
        }

        UUID documentId = UUID.randomUUID();
        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);
        String storedFileName = documentId + "." + extension;
        Path destination = storageDirectory.resolve(storedFileName).normalize();

        if (!destination.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("Nom de fichier invalide");
        }

        Files.createDirectories(storageDirectory);
        file.transferTo(destination);

        long fileSize = Files.size(destination);
        String sha256Hash = sha256Hex(destination);
        String compressedBase64Content = null;
        boolean contentCompressed = false;
        long storedSize = 0;
        long compressedSizeBytes = 0;
        long base64SizeBytes = 0;

        if (maxDatabaseBase64FileSize > 0 && fileSize <= maxDatabaseBase64FileSize) {
            compressedBase64Content = compressToBase64(destination);
            contentCompressed = true;
            storedSize = compressedBase64Content.length();
            base64SizeBytes = compressedBase64Content.length();
            compressedSizeBytes = Base64.getDecoder().decode(compressedBase64Content).length;
        }

        Document document = Document.builder()
                .id(documentId)
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
                .urlFile(downloadUrl(documentId))
                .storedFileName(storedFileName)
                .originalFileName(originalFileName)
                .contentType(detectedContentType)
                .fileSize(fileSize)
                .sha256Hash(sha256Hash)
                .originalSizeBytes(fileSize)
                .compressedSizeBytes(compressedSizeBytes)
                .base64SizeBytes(base64SizeBytes)
                .compressedBase64Content(compressedBase64Content)
                .contentCompressed(contentCompressed)
                .storedSize(storedSize)
                .build();

        Document savedDocument = documentsRespository.save(document);
        AnalysisView queuedAnalysis = analysisService.queueDocumentAnalysis(savedDocument.getId(), username);

        return toResponse(savedDocument, queuedAnalysis);
    }

    public List<DocumentResponseDTO> getDocuments(String username) {
        if (isCurrentUserAdmin()) {
            return documentsRespository.findAllDocumentResponses()
                    .stream()
                    .map(this::normalizeResponseDownloadUrl)
                    .toList();
        }

        return documentsRespository.findDocumentResponsesByUsername(username)
                .stream()
                .map(this::normalizeResponseDownloadUrl)
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

        if (filePath.startsWith(storageDirectory) && Files.exists(filePath)) {
            try {
                verifyHash(document, Files.readAllBytes(filePath));
                return new UrlResource(filePath.toUri());
            } catch (MalformedURLException exception) {
                throw new IllegalArgumentException("Chemin de fichier invalide");
            } catch (IOException exception) {
                throw new IllegalArgumentException("Impossible de vérifier l'intégrité du fichier");
            }
        }

        if (document.getCompressedBase64Content() != null && !document.getCompressedBase64Content().isBlank()) {
            try {
                Path temporaryDownloadFile = Files.createTempFile("antiplagiat-download-", tempFileSuffix(document));
                byte[] restoredContent = decompressBase64(document.getCompressedBase64Content(), document.isContentCompressed());
                verifyHash(document, restoredContent);
                Files.write(temporaryDownloadFile, restoredContent);
                return new UrlResource(temporaryDownloadFile.toUri());
            } catch (IOException exception) {
                throw new IllegalArgumentException("Contenu du document invalide");
            }
        }

        throw new EntityNotFoundException("Fichier introuvable");
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

    private String validateFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Type de fichier non autorisé. Formats acceptés : pdf, doc, docx, txt, png, jpg, jpeg, tif, tiff, bmp, gif, webp");
        }

        String detectedContentType;
        try (InputStream inputStream = file.getInputStream()) {
            detectedContentType = tika.detect(inputStream, originalFileName);
        }

        if (detectedContentType == null || !ALLOWED_MIME_TYPES.contains(detectedContentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Type MIME réel non autorisé : " + detectedContentType);
        }

        if ((extension.equals("pdf") && !detectedContentType.equalsIgnoreCase("application/pdf"))
                || ((extension.equals("jpg") || extension.equals("jpeg")) && !detectedContentType.equalsIgnoreCase("image/jpeg"))
                || (extension.equals("png") && !detectedContentType.equalsIgnoreCase("image/png"))
                || ((extension.equals("tif") || extension.equals("tiff")) && !detectedContentType.equalsIgnoreCase("image/tiff"))
                || (extension.equals("gif") && !detectedContentType.equalsIgnoreCase("image/gif"))
                || (extension.equals("webp") && !detectedContentType.equalsIgnoreCase("image/webp"))
                || (extension.equals("docx") && !detectedContentType.equalsIgnoreCase("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                || (extension.equals("doc") && !detectedContentType.equalsIgnoreCase("application/msword"))
                || (extension.equals("txt") && !detectedContentType.equalsIgnoreCase("text/plain"))) {
            throw new IllegalArgumentException("L'extension du fichier ne correspond pas à son contenu réel");
        }

        return detectedContentType;
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

    private String compressToBase64(Path filePath) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream inputStream = Files.newInputStream(filePath);
             GZIPOutputStream gzipOutputStream = new GZIPOutputStream(Base64.getEncoder().wrap(output))) {
            inputStream.transferTo(gzipOutputStream);
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] getDocumentBytes(Document document) throws IOException {
        if (document.getCompressedBase64Content() != null && !document.getCompressedBase64Content().isBlank()) {
            return decompressBase64(document.getCompressedBase64Content(), document.isContentCompressed());
        }

        Path filePath = storageDirectory.resolve(document.getStoredFileName()).normalize();
        if (!filePath.startsWith(storageDirectory) || !Files.exists(filePath)) {
            throw new EntityNotFoundException("Fichier introuvable");
        }

        byte[] content = Files.readAllBytes(filePath);
        verifyHash(document, content);
        return content;
    }

    private byte[] decompressBase64(String content, boolean compressed) throws IOException {
        byte[] decoded = Base64.getDecoder().decode(content);
        if (!compressed) {
            return decoded;
        }

        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
            return gzipInputStream.readAllBytes();
        }
    }

    private String tempFileSuffix(Document document) {
        String name = document.getOriginalFileName() != null ? document.getOriginalFileName() : document.getStoredFileName();
        if (name == null) {
            return ".bin";
        }

        int extensionIndex = name.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == name.length() - 1) {
            return ".bin";
        }

        return name.substring(extensionIndex);
    }

    private String downloadUrl(UUID documentId) {
        return "/api/documents/" + documentId + "/download";
    }

    private void normalizeDownloadUrl(Document document) {
        if (document.getId() == null) {
            return;
        }

        String expectedUrl = downloadUrl(document.getId());
        String currentUrl = document.getUrlFile();

        if (currentUrl == null
                || currentUrl.isBlank()
                || currentUrl.startsWith(PENDING_DOCUMENT_URL_PREFIX)) {
            document.setUrlFile(expectedUrl);
        }
    }

    private DocumentResponseDTO toResponse(Document document) {
        return toResponse(document, null);
    }

    private DocumentResponseDTO toResponse(Document document, AnalysisView analysis) {
        normalizeDownloadUrl(document);

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
                document.getFileSize(),
                document.getSha256Hash(),
                document.getOriginalSizeBytes(),
                document.getCompressedSizeBytes(),
                document.getBase64SizeBytes(),
                analysis == null ? null : analysis.id(),
                analysis == null ? null : analysis.status()
        );
    }

    private DocumentResponseDTO normalizeResponseDownloadUrl(DocumentResponseDTO document) {
        String expectedUrl = downloadUrl(document.id());
        String currentUrl = document.urlFile();

        if (currentUrl != null
                && !currentUrl.isBlank()
                && !currentUrl.startsWith(PENDING_DOCUMENT_URL_PREFIX)) {
            return document;
        }

        return new DocumentResponseDTO(
                document.id(),
                document.name(),
                document.faculty(),
                document.department(),
                document.author(),
                document.director(),
                document.rapporteur(),
                document.yearOfAcademic(),
                document.academic(),
                document.matriculation(),
                document.creationDate(),
                document.userId(),
                expectedUrl,
                document.originalFileName(),
                document.contentType(),
                document.fileSize(),
                document.sha256Hash(),
                document.originalSizeBytes(),
                document.compressedSizeBytes(),
                document.base64SizeBytes(),
                document.analysisId(),
                document.analysisStatus()
        );
    }

    private String sha256Hex(Path filePath) throws IOException {
        return sha256Hex(Files.readAllBytes(filePath));
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 non disponible", exception);
        }
    }

    private void verifyHash(Document document, byte[] bytes) {
        if (document.getSha256Hash() == null || document.getSha256Hash().isBlank()) {
            return;
        }

        String actualHash = sha256Hex(bytes);
        if (!document.getSha256Hash().equalsIgnoreCase(actualHash)) {
            throw new IllegalArgumentException("Hash SHA-256 du document invalide");
        }
    }
}
