package com.b2la.antiplagiat.controller;

import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.dto.ReportRequestDTO;
import com.b2la.antiplagiat.dto.ReportResponseDTO;
import com.b2la.antiplagiat.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReportResponseDTO>> generate(@RequestBody ReportRequestDTO req, Authentication authentication) {
        ReportResponseDTO dto = reportService.generateReport(req, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>("success", "Rapport généré", dto));
    }

    @GetMapping
    public ApiResponse<List<ReportResponseDTO>> list(Authentication authentication) {
        return new ApiResponse<>("success", reportService.getReports(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReportResponseDTO> getById(@PathVariable UUID id, Authentication authentication) {
        return new ApiResponse<>("success", reportService.getReport(id, authentication.getName()));
    }
}
