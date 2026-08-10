package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.SubscriptionRequestDTO;
import com.b2la.antiplagiat.dto.SubscriptionResponseDTO;
import com.b2la.antiplagiat.entites.Subscription;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.repository.SubscriptionRepository;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UsersRepository usersRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, UsersRepository usersRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.usersRepository = usersRepository;
    }

    public SubscriptionResponseDTO createSubscription(SubscriptionRequestDTO req, String username) {
        Users user = usersRepository.findByUsername(username).orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));

        Subscription sub = Subscription.builder()
                .type(req.type())
                .user(user)
                .active(true)
                .startAt(req.startAt() == null ? LocalDateTime.now() : req.startAt())
                .endAt(req.endAt())
                .build();

        // quotas by type
        switch (req.type().toUpperCase()) {
            case "FREE" -> {
                sub.setQuotaAntiPlagiarism(50);
                sub.setQuotaAntiAi(0);
            }
            case "PRO" -> {
                sub.setQuotaAntiPlagiarism(Integer.MAX_VALUE);
                sub.setQuotaAntiAi(100);
            }
            case "PREMIUM" -> {
                sub.setQuotaAntiPlagiarism(Integer.MAX_VALUE);
                sub.setQuotaAntiAi(Integer.MAX_VALUE);
            }
            default -> throw new IllegalArgumentException("Type d'abonnement inconnu");
        }

        Subscription saved = subscriptionRepository.save(sub);
        return toResponse(saved);
    }

    public List<SubscriptionResponseDTO> getSubscriptions(String username) {
        Users user = usersRepository.findByUsername(username).orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        return subscriptionRepository.findByUser(user).stream().map(this::toResponse).toList();
    }

    public SubscriptionResponseDTO getSubscription(UUID id, String username) {
        Subscription s = subscriptionRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Abonnement introuvable"));
        if (!s.getUser().getUsername().equals(username)) throw new SecurityException("Accès refusé");
        return toResponse(s);
    }

    public SubscriptionResponseDTO updateSubscription(UUID id, SubscriptionRequestDTO req, String username) {
        Subscription s = subscriptionRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Abonnement introuvable"));
        if (!s.getUser().getUsername().equals(username)) throw new SecurityException("Accès refusé");
        if (req.type() != null) s.setType(req.type());
        s.setStartAt(req.startAt());
        s.setEndAt(req.endAt());
        Subscription saved = subscriptionRepository.save(s);
        return toResponse(saved);
    }

    public void deleteSubscription(UUID id, String username) {
        Subscription s = subscriptionRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Abonnement introuvable"));
        if (!s.getUser().getUsername().equals(username)) throw new SecurityException("Accès refusé");
        subscriptionRepository.delete(s);
    }

    private SubscriptionResponseDTO toResponse(Subscription s) {
        return new SubscriptionResponseDTO(
                s.getId(),
                s.getType(),
                s.getQuotaAntiPlagiarism(),
                s.getQuotaAntiAi(),
                s.getUser().getId(),
                s.getUser().getUsername(),
                s.isActive(),
                s.getStartAt(),
                s.getEndAt(),
                s.getCreatedAt()
        );
    }
}
