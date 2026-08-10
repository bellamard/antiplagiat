package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.ReportRequestDTO;
import com.b2la.antiplagiat.dto.ReportResponseDTO;
import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Report;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.ReportRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReportService {

    private final ReportRepository reportRepository;
    private final AnalysisHistoryRepository historyRepository;

    public ReportService(ReportRepository reportRepository, AnalysisHistoryRepository historyRepository) {
        this.reportRepository = reportRepository;
        this.historyRepository = historyRepository;
    }

    public ReportResponseDTO generateReport(ReportRequestDTO req, String username) {
        AnalysisHistory history = historyRepository.findById(req.analysisId()).orElseThrow(() -> new EntityNotFoundException("Analyse introuvable"));
        if (!history.getUser().getUsername().equals(username)) throw new SecurityException("Accès refusé");

        // simple JSON content
        String content = String.format("{\"analysisId\":\"%s\",\"documentId\":\"%s\",\"overallScore\":%s,\"aiScore\":%s,\"details\":%s}",
                history.getId(), history.getDocument().getId(), history.getOverallScore(), history.getAiScore(), history.getDetails() == null ? "null" : history.getDetails());

        Report r = Report.builder()
                .analysis(history)
                .document(history.getDocument())
                .user(history.getUser())
                .content(content)
                .build();

        Report saved = reportRepository.save(r);
        return toResponse(saved);
    }

    public List<ReportResponseDTO> getReports(String username) {
        return reportRepository.findAll().stream()
                .filter(r -> r.getUser() != null && r.getUser().getUsername().equals(username))
                .map(this::toResponse)
                .toList();
    }

    public ReportResponseDTO getReport(UUID id, String username) {
        Report r = reportRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Rapport introuvable"));
        if (!r.getUser().getUsername().equals(username)) throw new SecurityException("Accès refusé");
        return toResponse(r);
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
