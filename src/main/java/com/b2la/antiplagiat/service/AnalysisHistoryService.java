package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.AnalysisHistoryRequestDTO;
import com.b2la.antiplagiat.dto.AnalysisHistoryResponseDTO;
import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AnalysisHistoryService {

    private final AnalysisHistoryRepository historyRepository;
    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;

    public AnalysisHistoryService(AnalysisHistoryRepository historyRepository, DocumentsRespository documentsRespository, UsersRepository usersRepository) {
        this.historyRepository = historyRepository;
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
    }

    public AnalysisHistoryResponseDTO createHistory(AnalysisHistoryRequestDTO req, String username) {
        Document doc = documentsRespository.findById(req.documentId()).orElseThrow(() -> new EntityNotFoundException("Document introuvable"));
        Users user = usersRepository.findByUsername(username).orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));

        AnalysisHistory h = AnalysisHistory.builder()
                .document(doc)
                .user(user)
                .overallScore(req.overallScore())
                .aiScore(req.aiScore())
                .details(req.details())
                .build();

        return toResponse(historyRepository.save(h));
    }

    public List<AnalysisHistoryResponseDTO> getHistories(String username) {
        Users user = usersRepository.findByUsername(username).orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        return historyRepository.findByUser(user).stream().map(this::toResponse).toList();
    }

    public AnalysisHistoryResponseDTO getHistory(UUID id, String username) {
        AnalysisHistory h = historyRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));
        if (!h.getUser().getUsername().equals(username)) throw new SecurityException("Accès refusé");
        return toResponse(h);
    }

    private AnalysisHistoryResponseDTO toResponse(AnalysisHistory h) {
        return new AnalysisHistoryResponseDTO(
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
