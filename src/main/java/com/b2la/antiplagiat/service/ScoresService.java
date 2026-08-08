package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.ScoreRequestDTO;
import com.b2la.antiplagiat.dto.ScoreResponseDTO;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Scores;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.repository.DocumentsRespository;
import com.b2la.antiplagiat.repository.ScoresRepository;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ScoresService {

    private final ScoresRepository scoresRepository;
    private final DocumentsRespository documentsRespository;
    private final UsersRepository usersRepository;

    public ScoresService(
            ScoresRepository scoresRepository,
            DocumentsRespository documentsRespository,
            UsersRepository usersRepository
    ) {
        this.scoresRepository = scoresRepository;
        this.documentsRespository = documentsRespository;
        this.usersRepository = usersRepository;
    }

    public ScoreResponseDTO createScore(ScoreRequestDTO request) {
        validateScoreRequest(request);

        Document document = findDocument(request.documentId());

        Scores score = Scores.builder()
                .document(document)
                .user(document.getUser())
                .overallScore(request.overallScore())
                .aiScore(request.aiScore())
                .build();

        return toResponse(scoresRepository.save(score));
    }

    public List<ScoreResponseDTO> getScores(String username) {
        if (isCurrentUserAdmin()) {
            return scoresRepository.findAll()
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        Users user = findUser(username);
        return scoresRepository.findByUser(user)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ScoreResponseDTO getScoreById(UUID id, String username) {
        Scores score = findScore(id);
        assertCanAccess(score, username);
        return toResponse(score);
    }

    public List<ScoreResponseDTO> getScoresByDocument(UUID documentId, String username) {
        Document document = findDocument(documentId);
        assertCanAccess(document, username);

        return scoresRepository.findByDocument(document)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ScoreResponseDTO updateScore(UUID id, ScoreRequestDTO request) {
        Scores score = findScore(id);

        if (request.overallScore() < 0 || request.overallScore() > 100) {
            throw new IllegalArgumentException("Le score global doit être entre 0 et 100");
        }
        if (request.aiScore() < 0 || request.aiScore() > 100) {
            throw new IllegalArgumentException("Le score IA doit être entre 0 et 100");
        }

        score.setOverallScore(request.overallScore());
        score.setAiScore(request.aiScore());

        if (request.documentId() != null && !request.documentId().equals(score.getDocument().getId())) {
            Document document = findDocument(request.documentId());
            score.setDocument(document);
            score.setUser(document.getUser());
        }

        return toResponse(scoresRepository.save(score));
    }

    public void deleteScore(UUID id) {
        Scores score = findScore(id);
        scoresRepository.delete(score);
    }

    private void validateScoreRequest(ScoreRequestDTO request) {
        if (request.documentId() == null) {
            throw new IllegalArgumentException("Le document est obligatoire");
        }
        if (request.overallScore() < 0 || request.overallScore() > 100) {
            throw new IllegalArgumentException("Le score global doit être entre 0 et 100");
        }
        if (request.aiScore() < 0 || request.aiScore() > 100) {
            throw new IllegalArgumentException("Le score IA doit être entre 0 et 100");
        }
    }

    private Document findDocument(UUID id) {
        return documentsRespository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable"));
    }

    private Scores findScore(UUID id) {
        return scoresRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Score introuvable"));
    }

    private Users findUser(String username) {
        return usersRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
    }

    private void assertCanAccess(Scores score, String username) {
        assertCanAccess(score.getDocument(), username);
    }

    private void assertCanAccess(Document document, String username) {
        if (isCurrentUserAdmin() || document.getUser().getUsername().equals(username)) {
            return;
        }

        throw new SecurityException("Accès refusé à ce score");
    }

    private boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        return authentication != null
                && authentication.getAuthorities()
                .stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private ScoreResponseDTO toResponse(Scores score) {
        return new ScoreResponseDTO(
                score.getId(),
                score.getOverallScore(),
                score.getAiScore(),
                score.getDocument().getId(),
                score.getDocument().getName(),
                score.getUser().getId(),
                score.getUser().getUsername(),
                score.getCreatedAt()
        );
    }
}
