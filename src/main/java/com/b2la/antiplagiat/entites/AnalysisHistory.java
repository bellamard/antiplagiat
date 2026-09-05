package com.b2la.antiplagiat.entites;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "analysis_history")
public class AnalysisHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(nullable = false)
    @Builder.Default
    private double overallScore = 0.0;

    @Column(nullable = false)
    @Builder.Default
    private double aiScore = 0.0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(length = 64)
    private String failedStep;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private int attempts = 0;

    @Builder.Default
    private int maxAttempts = 1;

    @Column(columnDefinition = "TEXT")
    private String details; // JSON details or analysis metadata

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
