package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Subscription;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    List<Subscription> findByUser(Users user);
    Optional<Subscription> findFirstByUserOrderByCreatedAtDesc(Users user);
}
