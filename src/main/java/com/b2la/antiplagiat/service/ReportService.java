package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.ReportRequestDTO;
import com.b2la.antiplagiat.dto.ReportResponseDTO;
import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Report;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.ReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReportService {

    private final ReportRepository reportRepository;
    private final AnalysisHistoryRepository historyRepository;
    private final DocumentsRespository documentsRespository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportService(
            ReportRepository reportRepository,
            AnalysisHistoryRepository historyRepository,
            DocumentsRespository documentsRespository
    ) {
        this.reportRepository = reportRepository;
        this.historyRepository = historyRepository;
        this.documentsRespository = documentsRespository;
    }

    public ReportResponseDTO generateReport(ReportRequestDTO req, String username) {
        AnalysisHistory history = resolveAnalysis(req);
        if (!history.getUser().getUsername().equals(username) && !com.b2la.antiplagiat.util.SecurityUtils.isCurrentUserAdmin()) throw new SecurityException("Accès refusé");

        String content = buildReportContent(history);

        Report r = Report.builder()
                .analysis(history)
                .document(history.getDocument())
                .user(history.getUser())
                .content(content)
                .build();

        Report saved = reportRepository.save(r);
        if (Boolean.TRUE.equals(req.clearBase64Content())) {
            clearBase64Content(history.getDocument());
        }

        return toResponse(saved);
    }

    public List<ReportResponseDTO> getReports(String username) {
        if (com.b2la.antiplagiat.util.SecurityUtils.isCurrentUserAdmin()) {
            return reportRepository.findAll().stream().map(this::toResponse).toList();
        }
        return reportRepository.findAll().stream()
                .filter(r -> r.getUser() != null && r.getUser().getUsername().equals(username))
                .map(this::toResponse)
                .toList();
    }

    public ReportResponseDTO getReport(UUID id, String username) {
        Report r = reportRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Rapport introuvable"));
        if (!r.getUser().getUsername().equals(username) && !com.b2la.antiplagiat.util.SecurityUtils.isCurrentUserAdmin()) throw new SecurityException("Accès refusé");
        return toResponse(r);
    }

    private AnalysisHistory resolveAnalysis(ReportRequestDTO req) {
        if (req == null) {
            throw new IllegalArgumentException("analysisId ou documentId est obligatoire");
        }

        if (req.analysisId() != null) {
            return historyRepository.findById(req.analysisId())
                    .orElseThrow(() -> new EntityNotFoundException("Analyse introuvable"));
        }

        if (req.documentId() != null) {
            Document document = documentsRespository.findById(req.documentId())
                    .orElseThrow(() -> new EntityNotFoundException("Document introuvable"));

            return historyRepository.findFirstByDocumentOrderByCreatedAtDesc(document)
                    .orElseThrow(() -> new EntityNotFoundException("Aucune analyse trouvée pour ce document"));
        }

        throw new IllegalArgumentException("analysisId ou documentId est obligatoire");
    }

    private String buildReportContent(AnalysisHistory history) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("analysisId", history.getId().toString());
        root.put("documentId", history.getDocument().getId().toString());
        root.put("documentName", history.getDocument().getName());
        root.put("documentBase64Present", hasBase64Content(history.getDocument()));
        root.put("overallScore", history.getOverallScore());
        root.put("aiScore", history.getAiScore());

        if (history.getDetails() == null || history.getDetails().isBlank()) {
            root.putNull("details");
        } else {
            try {
                root.set("details", objectMapper.readTree(history.getDetails()));
            } catch (IOException ignored) {
                root.put("details", history.getDetails());
            }
        }

        return root.toString();
    }

    private boolean hasBase64Content(Document document) {
        return document.getCompressedBase64Content() != null
                && !document.getCompressedBase64Content().isBlank();
    }

    private void clearBase64Content(Document document) {
        document.setCompressedBase64Content(null);
        document.setContentCompressed(false);
        document.setStoredSize(0);
        documentsRespository.save(document);
    }

    private ReportResponseDTO toResponse(Report r) {
        return new ReportResponseDTO(
                r.getId(),
                r.getAnalysis() == null ? null : r.getAnalysis().getId(),
                r.getDocument() == null ? null : r.getDocument().getId(),
                r.getUser() == null ? null : r.getUser().getId(),
                r.getContent(),
                r.getCreatedAt()
        );
    }
}
