package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Scores;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoresRepository extends JpaRepository<Scores, UUID> {
    List<Scores> findByUser(Users user);

    List<Scores> findByDocument(Document document);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    List<Scores> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"document", "user", "status"})
    List<Scores> findByUserUsernameOrderByCreatedAtDesc(String username);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    List<Scores> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

    @EntityGraph(attributePaths = {"document", "user", "status"})
    @Query("select s from Scores s where s.id = :id")
    Optional<Scores> findByIdWithRelations(@Param("id") UUID id);

    Optional<Scores> findFirstByDocumentOrderByCreatedAtDesc(Document document);

    boolean existsByDocument(Document document);
}
