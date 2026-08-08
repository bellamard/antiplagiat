package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Scores;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoresRepository extends JpaRepository<Scores, UUID> {
    List<Scores> findByUser(Users user);

    List<Scores> findByDocument(Document document);

    Optional<Scores> findFirstByDocumentOrderByCreatedAtDesc(Document document);

    boolean existsByDocument(Document document);
}
