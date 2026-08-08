package com.b2la.antiplagiat.entites;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "Document")
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String faculty;

    @Column(nullable = false)
    private String department;

    @Column(nullable = false)
    private String author;

    private String director;

    private String rapporteur;

    @Column(nullable = false)
    private String yearOfAcademic;

    private String academic;

    @Column(nullable = false, unique = true)
    private String matriculation;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime creationDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(nullable = false)
    private String urlFile;

    @Column(nullable = false)
    private String storedFileName;

    @Column(nullable = false)
    private String originalFileName;

    private String contentType;

    @Column(nullable = false)
    private long fileSize;
}
