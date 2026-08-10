package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Report;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    List<Report> findByUser(Users user);
}
