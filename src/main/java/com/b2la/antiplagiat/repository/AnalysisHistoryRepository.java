package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.entites.Document;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, UUID> {
    List<AnalysisHistory> findByUser(Users user);
    List<AnalysisHistory> findByDocument(Document document);
}
