package com.b2la.antiplagiat.controller;

import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.dto.SubscriptionRequestDTO;
import com.b2la.antiplagiat.dto.SubscriptionResponseDTO;
import com.b2la.antiplagiat.service.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionResponseDTO>> create(@RequestBody SubscriptionRequestDTO req, Authentication authentication) {
        SubscriptionResponseDTO dto = subscriptionService.createSubscription(req, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>("success", "Abonnement créé", dto));
    }

    @GetMapping
    public ApiResponse<List<SubscriptionResponseDTO>> getAll(Authentication authentication) {
        return new ApiResponse<>("success", subscriptionService.getSubscriptions(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ApiResponse<SubscriptionResponseDTO> getById(@PathVariable UUID id, Authentication authentication) {
        return new ApiResponse<>("success", subscriptionService.getSubscription(id, authentication.getName()));
    }

    @PutMapping("/{id}")
    public ApiResponse<SubscriptionResponseDTO> update(@PathVariable UUID id, @RequestBody SubscriptionRequestDTO req, Authentication authentication) {
        return new ApiResponse<>("success", "Abonnement modifié", subscriptionService.updateSubscription(id, req, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id, Authentication authentication) {
        subscriptionService.deleteSubscription(id, authentication.getName());
        return ResponseEntity.ok(new ApiResponse<>("success", "Abonnement supprimé"));
    }
}
