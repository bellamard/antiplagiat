package com.b2la.antiplagiat.analysis.application;

import com.b2la.antiplagiat.analysis.domain.AnalysisResult;
import com.b2la.antiplagiat.analysis.domain.PlagiarismDetector;
import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Scores;
import com.b2la.antiplagiat.entites.Status;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.ScoresRepository;
import com.b2la.antiplagiat.repository.StatusRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AnalysisWorkerService {

    private final AnalysisHistoryRepository historyRepository;
    private final PlagiarismDetector plagiarismDetector;
    private final ScoresRepository scoresRepository;
    private final StatusRepository statusRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisWorkerService(
            AnalysisHistoryRepository historyRepository,
            PlagiarismDetector plagiarismDetector,
            ScoresRepository scoresRepository,
            StatusRepository statusRepository
    ) {
        this.historyRepository = historyRepository;
        this.plagiarismDetector = plagiarismDetector;
        this.scoresRepository = scoresRepository;
        this.statusRepository = statusRepository;
    }

    @Async
    public void process(UUID historyId) {
        AnalysisHistory history = historyRepository.findByIdWithRelations(historyId)
                .orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));

        if (history.getStatus().getLibelle() == StatusEnum.CANCELLED) {
            return;
        }

        history.setStatus(status(StatusEnum.PROCESSING));
        history.setStartedAt(LocalDateTime.now());
        history.setDetails("{\"status\":\"PROCESSING\"}");
        historyRepository.save(history);

        try {
            AnalysisResult result = plagiarismDetector.analyze(history.getDocument());
            StatusEnum finalStatus = resolveFinalStatus(result);

            AnalysisHistory latest = historyRepository.findByIdWithRelations(historyId)
                    .orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));
            if (latest.getStatus().getLibelle() == StatusEnum.CANCELLED) {
                return;
            }

            latest.setOverallScore(result.getOverallScore());
            latest.setAiScore(result.getAiScore());
            latest.setDetails(result.getDetails());
            latest.setStatus(status(finalStatus));
            latest.setFailedStep(extractText(result.getDetails(), "failedStep"));
            latest.setErrorMessage(extractText(result.getDetails(), "errorMessage"));
            latest.setFinishedAt(LocalDateTime.now());
            AnalysisHistory saved = historyRepository.save(latest);

            if (finalStatus == StatusEnum.COMPLETED || finalStatus == StatusEnum.DEGRADED) {
                syncScore(saved, finalStatus);
            }
        } catch (Exception exception) {
            AnalysisHistory failed = historyRepository.findByIdWithRelations(historyId)
                    .orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));
            failed.setStatus(status(StatusEnum.FAILED));
            failed.setFailedStep("analysis");
            failed.setErrorMessage(exception.getMessage());
            failed.setDetails(errorDetails(exception));
            failed.setFinishedAt(LocalDateTime.now());
            historyRepository.save(failed);
        }
    }

    private StatusEnum resolveFinalStatus(AnalysisResult result) {
        String details = result.getDetails();
        String status = extractText(details, "status");
        if (status == null) {
            status = extractText(details, "/details/status");
        }

        if ("FAILED".equalsIgnoreCase(status)) {
            return StatusEnum.FAILED;
        }
        if ("DEGRADED".equalsIgnoreCase(status)) {
            return StatusEnum.DEGRADED;
        }
        return StatusEnum.COMPLETED;
    }

    private String extractText(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (field.startsWith("/")) {
                JsonNode node = root.at(field);
                return node.isMissingNode() || node.isNull() ? null : node.asText();
            }
            JsonNode node = root.get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void syncScore(AnalysisHistory history, StatusEnum statusEnum) {
        Status status = status(statusEnum);
        if (scoresRepository.existsByDocument(history.getDocument())) {
            scoresRepository.findFirstByDocumentOrderByCreatedAtDesc(history.getDocument()).ifPresent(score -> {
                score.setOverallScore(history.getOverallScore());
                score.setAiScore(history.getAiScore());
                score.setStatus(status);
                scoresRepository.save(score);
            });
            return;
        }

        Scores score = Scores.builder()
                .document(history.getDocument())
                .user(history.getUser())
                .overallScore(history.getOverallScore())
                .aiScore(history.getAiScore())
                .status(status)
                .build();
        scoresRepository.save(score);
    }

    private Status status(StatusEnum status) {
        return statusRepository.findByLibelle(status)
                .orElseGet(() -> statusRepository.save(Status.builder().libelle(status).build()));
    }

    private String errorDetails(Exception exception) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "status", "FAILED",
                    "failedStep", "analysis",
                    "errorMessage", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            ));
        } catch (Exception ignored) {
            return "{\"status\":\"FAILED\",\"failedStep\":\"analysis\"}";
        }
    }
}
