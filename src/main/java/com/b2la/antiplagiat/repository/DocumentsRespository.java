package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentsRespository extends JpaRepository<Document, UUID> {
    Optional<Document> findByMatriculation(String matriculation);

    boolean existsByMatriculation(String matriculation);

    List<Document> findByUser(Users user);

    List<Document> findByUserId(UUID userId);

    List<Document> findByAuthor(String author);

    List<Document> findByYearOfAcademic(String yearOfAcademic);

    List<Document> findByDirector(String director);

    List<Document> findByRapporteur(String rapporteur);

    List<Document> findByAcademic(String academic);
}
