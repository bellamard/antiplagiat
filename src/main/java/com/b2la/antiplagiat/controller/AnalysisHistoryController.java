package com.b2la.antiplagiat.controller;

import com.b2la.antiplagiat.dto.AnalysisHistoryRequestDTO;
import com.b2la.antiplagiat.dto.AnalysisHistoryResponseDTO;
import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.service.AnalysisHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/histories")
public class AnalysisHistoryController {

    private final AnalysisHistoryService historyService;

    public AnalysisHistoryController(AnalysisHistoryService historyService) {
        this.historyService = historyService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AnalysisHistoryResponseDTO>> create(@RequestBody AnalysisHistoryRequestDTO req, Authentication authentication) {
        AnalysisHistoryResponseDTO dto = historyService.createHistory(req, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>("success", "Historique créé", dto));
    }

    @GetMapping
    public ApiResponse<List<AnalysisHistoryResponseDTO>> list(Authentication authentication) {
        return new ApiResponse<>("success", historyService.getHistories(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalysisHistoryResponseDTO> getById(@PathVariable UUID id, Authentication authentication) {
        return new ApiResponse<>("success", historyService.getHistory(id, authentication.getName()));
    }
}
