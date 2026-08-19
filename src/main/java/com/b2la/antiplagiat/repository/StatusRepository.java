package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Status;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StatusRepository extends JpaRepository<Status, Integer> {
    Optional<Status> findByLibelle(StatusEnum libelle);
}
