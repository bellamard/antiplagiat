package com.b2la.antiplagiat.analysis.api;

import com.b2la.antiplagiat.analysis.api.dto.AnalysisRequest;
import com.b2la.antiplagiat.analysis.api.dto.AnalysisResponse;
import com.b2la.antiplagiat.analysis.application.AnalysisCommand;
import com.b2la.antiplagiat.analysis.application.AnalysisService;
import com.b2la.antiplagiat.analysis.application.AnalysisView;
import com.b2la.antiplagiat.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/histories")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AnalysisResponse>> create(@RequestBody AnalysisRequest req, Authentication authentication) {
        AnalysisResponse dto = toResponse(analysisService.createHistory(
                new AnalysisCommand(req.matriculation()), authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>("success", "Historique créé", dto));
    }

    @GetMapping
    public ApiResponse<List<AnalysisResponse>> list(Authentication authentication) {
        return new ApiResponse<>("success", analysisService.getHistories(authentication.getName()).stream()
                .map(this::toResponse)
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalysisResponse> getById(@PathVariable UUID id, Authentication authentication) {
        return new ApiResponse<>("success", toResponse(analysisService.getHistory(id, authentication.getName())));
    }

    private AnalysisResponse toResponse(AnalysisView view) {
        return new AnalysisResponse(
                view.id(), view.documentId(), view.documentName(), view.userId(), view.username(),
                view.overallScore(), view.aiScore(), view.details(), view.createdAt()
        );
    }
}
