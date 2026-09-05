package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.time.LocalDateTime;

public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, UUID> {
    List<AnalysisHistory> findByUser(Users user);
    List<AnalysisHistory> findByDocument(Document document);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    List<AnalysisHistory> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"document", "user", "status"})
    List<AnalysisHistory> findByUserUsernameOrderByCreatedAtDesc(String username);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    @Query("select h from AnalysisHistory h where h.id = :id")
    Optional<AnalysisHistory> findByIdWithRelations(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    Optional<AnalysisHistory> findFirstByDocumentOrderByCreatedAtDesc(Document document);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    Optional<AnalysisHistory> findFirstByDocumentIdAndStatusLibelleInOrderByCreatedAtDesc(
            UUID documentId,
            Collection<StatusEnum> statuses
    );

    List<AnalysisHistory> findByStatusLibelleAndStartedAtBefore(StatusEnum status, LocalDateTime startedAt);

    void deleteByDocument(Document document);
}
