package com.b2la.antiplagiat.analysis.application;

import com.b2la.antiplagiat.analysis.domain.AnalysisResult;
import com.b2la.antiplagiat.analysis.domain.PlagiarismDetector;
import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.ScoresRepository;
import com.b2la.antiplagiat.repository.StatusRepository;
import com.b2la.antiplagiat.repository.UsersRepository;
import com.b2la.antiplagiat.util.SecurityUtils;
import com.b2la.antiplagiat.entites.Scores;
import com.b2la.antiplagiat.entites.Status;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AnalysisService {

    private final AnalysisHistoryRepository historyRepository;
    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;

    private final PlagiarismDetector plagiarismDetector;
    private final ScoresRepository scoresRepository;
    private final StatusRepository statusRepository;

    public AnalysisService(AnalysisHistoryRepository historyRepository, DocumentsRespository documentsRespository, UsersRepository usersRepository, PlagiarismDetector plagiarismDetector, ScoresRepository scoresRepository, StatusRepository statusRepository) {
        this.historyRepository = historyRepository;
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
        this.plagiarismDetector = plagiarismDetector;
        this.scoresRepository = scoresRepository;
        this.statusRepository = statusRepository;
    }

    public AnalysisView createHistory(AnalysisCommand command, String username) {
        Document doc = documentsRespository.findByMatriculation(command.matriculation()).orElseThrow(() -> new EntityNotFoundException("Document introuvable"));
        Users user = usersRepository.findByUsername(username).orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        
        // ensure the requesting user is owner of the document or an admin
        if (!doc.getUser().getUsername().equals(username) && !SecurityUtils.isCurrentUserAdmin()) {
            throw new SecurityException("Accès refusé");
        }

        // analyze document using Tika + external Python AI analyzer (fallbacks included)
        AnalysisResult result = plagiarismDetector.analyze(doc);

        AnalysisHistory h = AnalysisHistory.builder()
                .document(doc)
                .user(user)
                .overallScore(result.getOverallScore())
                .aiScore(result.getAiScore())
                .details(result.getDetails())
                .build();

        AnalysisHistory saved = historyRepository.save(h);

        // synchronize Scores: create or update latest score for the document to avoid duplicates/incoherences
        try {
            // if a score exists for this document, update the latest one
            if (scoresRepository.existsByDocument(doc)) {
                scoresRepository.findFirstByDocumentOrderByCreatedAtDesc(doc).ifPresent(s -> {
                    s.setOverallScore(result.getOverallScore());
                    s.setAiScore(result.getAiScore());
                    Status status = statusRepository.findByLibelle(StatusEnum.COMPLETED)
                            .orElseGet(() -> statusRepository.save(Status.builder().libelle(StatusEnum.COMPLETED).build()));
                    s.setStatus(status);
                    scoresRepository.save(s);
                });
            } else {
                Status status = statusRepository.findByLibelle(StatusEnum.COMPLETED)
                        .orElseGet(() -> statusRepository.save(Status.builder().libelle(StatusEnum.COMPLETED).build()));

                Scores newScore = Scores.builder()
                        .document(doc)
                        .user(user)
                        .overallScore(result.getOverallScore())
                        .aiScore(result.getAiScore())
                        .status(status)
                        .build();
                scoresRepository.save(newScore);
            }
        } catch (Exception ex) {
            // don't fail analysis creation because of score sync issues; log in future
        }

        return toResponse(saved);
    }

    public List<AnalysisView> getHistories(String username) {
        Users user = usersRepository.findByUsername(username).orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        return historyRepository.findByUser(user).stream().map(this::toResponse).toList();
    }

    public AnalysisView getHistory(UUID id, String username) {
        AnalysisHistory h = historyRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));
        if (!h.getUser().getUsername().equals(username) && !SecurityUtils.isCurrentUserAdmin()) throw new SecurityException("Accès refusé");
        return toResponse(h);
    }

    private AnalysisView toResponse(AnalysisHistory h) {
        return new AnalysisView(
                h.getId(),
                h.getDocument().getId(),
                h.getDocument().getName(),
                h.getUser().getId(),
                h.getUser().getUsername(),
                h.getOverallScore(),
                h.getAiScore(),
                h.getDetails(),
                h.getCreatedAt()
        );
    }
}
