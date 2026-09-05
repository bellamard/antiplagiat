package com.b2la.antiplagiat.analysis.application;

import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Status;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.StatusRepository;
import com.b2la.antiplagiat.repository.UsersRepository;
import com.b2la.antiplagiat.util.SecurityUtils;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AnalysisService {

    private static final Set<StatusEnum> ACTIVE_STATUSES = Set.of(StatusEnum.PENDING, StatusEnum.PROCESSING);

    private final AnalysisHistoryRepository historyRepository;
    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;
    private final StatusRepository statusRepository;
    private final AnalysisWorkerService analysisWorkerService;

    public AnalysisService(
            AnalysisHistoryRepository historyRepository,
            DocumentsRespository documentsRespository,
            UsersRepository usersRepository,
            StatusRepository statusRepository,
            AnalysisWorkerService analysisWorkerService
    ) {
        this.historyRepository = historyRepository;
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
        this.statusRepository = statusRepository;
        this.analysisWorkerService = analysisWorkerService;
    }

    public AnalysisView createHistory(AnalysisCommand command, String username) {
        Document document = documentsRespository.findByMatriculation(command.matriculation())
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable"));
        return queueDocumentAnalysis(document.getId(), username);
    }

    public AnalysisView queueDocumentAnalysis(UUID documentId, String username) {
        Document document = documentsRespository.findById(documentId)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable"));
        Users user = usersRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));

        assertCanAccess(document, username);

        historyRepository.findFirstByDocumentIdAndStatusLibelleInOrderByCreatedAtDesc(document.getId(), ACTIVE_STATUSES)
                .ifPresent(active -> {
                    throw new IllegalStateException("Une analyse est déjà en cours pour ce document");
                });

        AnalysisHistory history = AnalysisHistory.builder()
                .document(document)
                .user(user)
                .status(status(StatusEnum.PENDING))
                .details("{\"status\":\"PENDING\"}")
                .build();

        AnalysisHistory saved = historyRepository.saveAndFlush(history);
        runAfterCommit(saved.getId());
        return toResponse(saved);
    }

    public AnalysisView cancelAnalysis(UUID id, String username) {
        AnalysisHistory history = historyRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));
        assertCanAccess(history.getDocument(), username);

        StatusEnum current = history.getStatus().getLibelle();
        if (current == StatusEnum.COMPLETED || current == StatusEnum.FAILED || current == StatusEnum.DEGRADED) {
            throw new IllegalStateException("Impossible d'annuler une analyse déjà terminée");
        }

        history.setStatus(status(StatusEnum.CANCELLED));
        history.setFinishedAt(LocalDateTime.now());
        history.setDetails("{\"status\":\"CANCELLED\"}");
        return toResponse(historyRepository.save(history));
    }

    public List<AnalysisView> getHistories(String username) {
        if (SecurityUtils.isCurrentUserAdmin()) {
            return historyRepository.findAllByOrderByCreatedAtDesc()
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return historyRepository.findByUserUsernameOrderByCreatedAtDesc(username)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public AnalysisView getHistory(UUID id, String username) {
        AnalysisHistory history = historyRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new EntityNotFoundException("Historique introuvable"));
        assertCanAccess(history.getDocument(), username);
        return toResponse(history);
    }

    private void runAfterCommit(UUID historyId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            analysisWorkerService.process(historyId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                analysisWorkerService.process(historyId);
            }
        });
    }

    private void assertCanAccess(Document document, String username) {
        if (document.getUser().getUsername().equals(username) || SecurityUtils.isCurrentUserAdmin()) {
            return;
        }
        throw new SecurityException("Accès refusé");
    }

    private Status status(StatusEnum status) {
        return statusRepository.findByLibelle(status)
                .orElseGet(() -> statusRepository.save(Status.builder().libelle(status).build()));
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
                h.getStatus().getLibelle().name(),
                h.getFailedStep(),
                h.getErrorMessage(),
                h.getDetails(),
                h.getStartedAt(),
                h.getFinishedAt(),
                h.getCreatedAt()
        );
    }
}
