package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, UUID> {
    List<AnalysisHistory> findByUser(Users user);
    List<AnalysisHistory> findByDocument(Document document);

    @EntityGraph(attributePaths = {"document", "user"})
    List<AnalysisHistory> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"document", "user"})
    List<AnalysisHistory> findByUserUsernameOrderByCreatedAtDesc(String username);

    @EntityGraph(attributePaths = {"document", "user"})
    @Query("select h from AnalysisHistory h where h.id = :id")
    Optional<AnalysisHistory> findByIdWithRelations(@Param("id") UUID id);

    Optional<AnalysisHistory> findFirstByDocumentOrderByCreatedAtDesc(Document document);
}
