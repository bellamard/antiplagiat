package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.DocumentResponseDTO;
import com.b2la.antiplagiat.analysis.domain.AnalysisResult;
import com.b2la.antiplagiat.analysis.domain.PlagiarismDetector;
import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Scores;
import com.b2la.antiplagiat.entites.Status;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.ScoresRepository;
import com.b2la.antiplagiat.repository.StatusRepository;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final String PENDING_DOCUMENT_URL_PREFIX = "/api/documents/pending";

    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;
    private final PlagiarismDetector plagiarismDetector;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final ScoresRepository scoresRepository;
    private final StatusRepository statusRepository;
    private final Path storageDirectory;
    private final long maxDatabaseBase64FileSize;

    public DocumentService(
            DocumentsRespository documentsRespository,
            UsersRepository usersRepository,
            PlagiarismDetector plagiarismDetector,
            AnalysisHistoryRepository analysisHistoryRepository,
            ScoresRepository scoresRepository,
            StatusRepository statusRepository,
            @Value("${app.documents.storage-dir:uploads/documents}") String storageDirectory,
            @Value("${app.documents.database-content-max-size-bytes:0}") long maxDatabaseBase64FileSize
    ) {
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
        this.plagiarismDetector = plagiarismDetector;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.scoresRepository = scoresRepository;
        this.statusRepository = statusRepository;
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
        validateFile(file);

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
        String compressedBase64Content = null;
        boolean contentCompressed = false;
        long storedSize = 0;

        if (maxDatabaseBase64FileSize > 0 && fileSize <= maxDatabaseBase64FileSize) {
            compressedBase64Content = compressToBase64(destination);
            contentCompressed = true;
            storedSize = compressedBase64Content.length();
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
                .contentType(file.getContentType())
                .fileSize(fileSize)
                .compressedBase64Content(compressedBase64Content)
                .contentCompressed(contentCompressed)
                .storedSize(storedSize)
                .build();

        Document savedDocument = documentsRespository.save(document);

        AnalysisResult analysisResult = plagiarismDetector.analyze(savedDocument);
        persistAnalysisResult(savedDocument, user, analysisResult);

        return toResponse(savedDocument);
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
                return new UrlResource(filePath.toUri());
            } catch (MalformedURLException exception) {
                throw new IllegalArgumentException("Chemin de fichier invalide");
            }
        }

        if (document.getCompressedBase64Content() != null && !document.getCompressedBase64Content().isBlank()) {
            try {
                Path temporaryDownloadFile = Files.createTempFile("antiplagiat-download-", tempFileSuffix(document));
                Files.write(temporaryDownloadFile, decompressBase64(document.getCompressedBase64Content(), document.isContentCompressed()));
                temporaryDownloadFile.toFile().deleteOnExit();
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

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String extension = getExtension(originalFileName);

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Type de fichier non autorisé. Formats acceptés : pdf, doc, docx, txt, png, jpg, jpeg, tif, tiff, bmp, gif, webp");
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

    private String compressToBase64(Path filePath) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream inputStream = Files.newInputStream(filePath);
             GZIPOutputStream gzipOutputStream = new GZIPOutputStream(Base64.getEncoder().wrap(output))) {
            inputStream.transferTo(gzipOutputStream);
        }
        return output.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public byte[] getDocumentBytes(Document document) throws IOException {
        if (document.getCompressedBase64Content() != null && !document.getCompressedBase64Content().isBlank()) {
            return decompressBase64(document.getCompressedBase64Content(), document.isContentCompressed());
        }

        Path filePath = storageDirectory.resolve(document.getStoredFileName()).normalize();
        if (!filePath.startsWith(storageDirectory) || !Files.exists(filePath)) {
            throw new EntityNotFoundException("Fichier introuvable");
        }

        return Files.readAllBytes(filePath);
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

    private void persistAnalysisResult(Document document, Users user, AnalysisResult result) {
        AnalysisHistory history = AnalysisHistory.builder()
                .document(document)
                .user(user)
                .overallScore(result.getOverallScore())
                .aiScore(result.getAiScore())
                .details(result.getDetails())
                .build();
        analysisHistoryRepository.save(history);

        Status status = statusRepository.findByLibelle(StatusEnum.COMPLETED)
                .orElseGet(() -> statusRepository.save(Status.builder().libelle(StatusEnum.COMPLETED).build()));

        if (scoresRepository.existsByDocument(document)) {
            scoresRepository.findFirstByDocumentOrderByCreatedAtDesc(document).ifPresent(score -> {
                score.setOverallScore(result.getOverallScore());
                score.setAiScore(result.getAiScore());
                score.setStatus(status);
                scoresRepository.save(score);
            });
            return;
        }

        Scores score = Scores.builder()
                .document(document)
                .user(user)
                .overallScore(result.getOverallScore())
                .aiScore(result.getAiScore())
                .status(status)
                .build();
        scoresRepository.save(score);
    }

    private DocumentResponseDTO toResponse(Document document) {
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
                document.getFileSize()
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
                document.fileSize()
        );
    }
}
