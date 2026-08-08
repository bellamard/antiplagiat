package com.b2la.antiplagiat.controller;

import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.dto.ScoreRequestDTO;
import com.b2la.antiplagiat.dto.ScoreResponseDTO;
import com.b2la.antiplagiat.service.ScoresService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scores")
public class ScoresController {

    private final ScoresService scoresService;

    public ScoresController(ScoresService scoresService) {
        this.scoresService = scoresService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ScoreResponseDTO>> createScore(@RequestBody ScoreRequestDTO request) {
        ScoreResponseDTO score = scoresService.createScore(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("success", "Score créé", score));
    }

    @GetMapping
    public ApiResponse<List<ScoreResponseDTO>> getScores(Authentication authentication) {
        return new ApiResponse<>("success", scoresService.getScores(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ScoreResponseDTO> getScoreById(@PathVariable UUID id, Authentication authentication) {
        return new ApiResponse<>("success", scoresService.getScoreById(id, authentication.getName()));
    }

    @GetMapping("/document/{documentId}")
    public ApiResponse<List<ScoreResponseDTO>> getScoresByDocument(
            @PathVariable UUID documentId,
            Authentication authentication
    ) {
        return new ApiResponse<>("success", scoresService.getScoresByDocument(documentId, authentication.getName()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ScoreResponseDTO> updateScore(@PathVariable UUID id, @RequestBody ScoreRequestDTO request) {
        return new ApiResponse<>("success", "Score modifié", scoresService.updateScore(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteScore(@PathVariable UUID id) {
        scoresService.deleteScore(id);
        return ResponseEntity.ok(new ApiResponse<>("success", "Score supprimé"));
    }
}
