package com.b2la.antiplagiat.config;

import com.b2la.antiplagiat.entites.Roles;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.enumerote.Role;
import com.b2la.antiplagiat.repository.RolesRepository;
import com.b2la.antiplagiat.repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;

@Component
@Transactional
public class DataSeeder implements CommandLineRunner {

    private final RolesRepository rolesRepository;
    private final UsersRepository usersRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SeedProperties seedProperties;

    @Autowired
    public DataSeeder(RolesRepository rolesRepository, UsersRepository usersRepository, BCryptPasswordEncoder passwordEncoder, SeedProperties seedProperties) {
        this.rolesRepository = rolesRepository;
        this.usersRepository = usersRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedProperties = seedProperties;
    }

    @Override
    public void run(String... args) throws Exception {
        // Seed roles from properties if provided
        if (seedProperties.getRoles() != null && !seedProperties.getRoles().isEmpty()) {
            // create missing roles only
            var existing = rolesRepository.findAll();
            var existingSet = existing.stream().map(Roles::getLibelle).collect(java.util.stream.Collectors.toSet());
            var toCreate = seedProperties.getRoles().stream()
                    .map(r -> Role.valueOf(r.toUpperCase()))
                    .filter(roleEnum -> !existingSet.contains(roleEnum))
                    .map(roleEnum -> Roles.builder().libelle(roleEnum).build())
                    .toList();
            if (!toCreate.isEmpty()) {
                rolesRepository.saveAll(toCreate);
            }
        }

        // If no roles present in DB, fall back to defaults
        if (rolesRepository.count() == 0) {
            Roles admin = Roles.builder().libelle(Role.ADMIN).build();
            Roles teacher = Roles.builder().libelle(Role.TEACHER).build();
            Roles student = Roles.builder().libelle(Role.STUDENT).build();
            rolesRepository.saveAll(Arrays.asList(admin, teacher, student));
        }

        // build role map (defensive: merge duplicates if any)
        var rolesMap = rolesRepository.findAll().stream().collect(java.util.stream.Collectors.toMap(Roles::getLibelle, r -> r, (a, b) -> a));

        // Seed users from properties
        if (seedProperties.getUsers() != null && !seedProperties.getUsers().isEmpty()) {
            for (SeedProperties.UserSeed us : seedProperties.getUsers()) {
                if (us.getUsername() == null || us.getUsername().isBlank()) continue;
                if (usersRepository.existsByUsername(us.getUsername())) continue;
                Role roleEnum = Role.valueOf((us.getRole() != null ? us.getRole() : "STUDENT").toUpperCase());
                Roles roleEntity = rolesMap.get(roleEnum);
                if (roleEntity == null) {
                    throw new IllegalStateException("Role entity missing for: " + roleEnum);
                }
                Users user = Users.builder()
                        .username(us.getUsername())
                        .email(us.getEmail())
                        .phoneNumber(us.getPhoneNumber())
                        .password(passwordEncoder.encode(us.getPassword() != null ? us.getPassword() : "Password@2026"))
                        .firstName(us.getFirstName())
                        .lastName(us.getLastName())
                        .role(roleEntity)
                        .build();
                usersRepository.save(user);
            }
        }
    }
}
