package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.dto.DocumentResponseDTO;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentsRespository extends JpaRepository<Document, UUID> {
    Optional<Document> findByMatriculation(String matriculation);

    boolean existsByMatriculation(String matriculation);

    @Query("""
            select new com.b2la.antiplagiat.dto.DocumentResponseDTO(
                d.id,
                d.name,
                d.faculty,
                d.department,
                d.author,
                d.director,
                d.rapporteur,
                d.yearOfAcademic,
                d.academic,
                d.matriculation,
                d.creationDate,
                u.id,
                d.urlFile,
                d.originalFileName,
                d.contentType,
                d.fileSize
            )
            from Document d
            join d.user u
            order by d.creationDate desc
            """)
    List<DocumentResponseDTO> findAllDocumentResponses();

    @Query("""
            select new com.b2la.antiplagiat.dto.DocumentResponseDTO(
                d.id,
                d.name,
                d.faculty,
                d.department,
                d.author,
                d.director,
                d.rapporteur,
                d.yearOfAcademic,
                d.academic,
                d.matriculation,
                d.creationDate,
                u.id,
                d.urlFile,
                d.originalFileName,
                d.contentType,
                d.fileSize
            )
            from Document d
            join d.user u
            where u.username = :username
            order by d.creationDate desc
            """)
    List<DocumentResponseDTO> findDocumentResponsesByUsername(@Param("username") String username);

    List<Document> findByUser(Users user);

    List<Document> findByUserId(UUID userId);

    List<Document> findByAuthor(String author);

    List<Document> findByYearOfAcademic(String yearOfAcademic);

    List<Document> findByDirector(String director);

    List<Document> findByRapporteur(String rapporteur);

    List<Document> findByAcademic(String academic);
}
