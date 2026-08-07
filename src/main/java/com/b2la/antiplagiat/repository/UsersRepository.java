package com.b2la.antiplagiat.repository;

import com.b2la.antiplagiat.entites.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UsersRepository extends JpaRepository<Users, UUID> {
    Users findByPhoneNumber(String phoneNumber);
    Users findByFirstName(String firstName);
}
