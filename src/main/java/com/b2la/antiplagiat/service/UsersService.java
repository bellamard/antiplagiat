package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.UsersDTO;
import com.b2la.antiplagiat.dto.UsersResponseDTO;
import com.b2la.antiplagiat.entites.Roles;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.enumerote.Role;
import com.b2la.antiplagiat.mapper.UsersDTOMapper;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class UsersService {

    private final UsersRepository usersRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final UsersDTOMapper usersDTOMapper;

    public UsersService(
            UsersRepository usersRepository,
            BCryptPasswordEncoder passwordEncoder,
            UsersDTOMapper usersDTOMapper
    ) {
        this.usersRepository = usersRepository;
        this.passwordEncoder = passwordEncoder;
        this.usersDTOMapper = usersDTOMapper;
    }

    public UsersResponseDTO addUser(UsersDTO request) {
        validateCreateRequest(request);

        if (usersRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new IllegalArgumentException("Le numéro de téléphone existe déjà");
        }

        if (usersRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Le nom d'utilisateur existe déjà");
        }

        if (usersRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("L'adresse email existe déjà");
        }

        Roles userRole = Roles.builder()
                .libelle(Role.STUDENT)
                .build();

        Users user = Users.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .phoneNumber(request.phoneNumber())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .surname(request.surname())
                .dateOfBirth(request.dateOfBirth())
                .role(userRole)
                .createdAt(LocalDateTime.now())
                .build();

        return usersDTOMapper.apply(usersRepository.save(user));
    }

    public List<UsersResponseDTO> getAllUsers() {
        return usersRepository.findAll()
                .stream()
                .map(usersDTOMapper)
                .toList();
    }

    public UsersResponseDTO getUserById(UUID id) {
        return usersDTOMapper.apply(findUserById(id));
    }

    public UsersResponseDTO updateUser(UUID id, UsersDTO request) {
        Users user = findUserById(id);

        if (request.username() != null && !request.username().isBlank()) {
            if (!Objects.equals(user.getUsername(), request.username())
                    && usersRepository.existsByUsername(request.username())) {
                throw new IllegalArgumentException("Le nom d'utilisateur existe déjà");
            }
            user.setUsername(request.username());
        }
        if (request.email() != null && !request.email().isBlank()) {
            if (!isValidEmail(request.email())) {
                throw new IllegalArgumentException("Adresse email invalide");
            }
            if (!Objects.equals(user.getEmail(), request.email())
                    && usersRepository.existsByEmail(request.email())) {
                throw new IllegalArgumentException("L'adresse email existe déjà");
            }
            user.setEmail(request.email());
        }
        if (request.firstName() != null) {
            user.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName());
        }
        if (request.surname() != null) {
            user.setSurname(request.surname());
        }
        if (request.dateOfBirth() != null) {
            user.setDateOfBirth(request.dateOfBirth());
        }
        if (request.phoneNumber() != null && !request.phoneNumber().isBlank()) {
            if (!isValidInternationalPhone(request.phoneNumber())) {
                throw new IllegalArgumentException("Numéro de téléphone invalide");
            }
            if (!Objects.equals(user.getPhoneNumber(), request.phoneNumber())
                    && usersRepository.existsByPhoneNumber(request.phoneNumber())) {
                throw new IllegalArgumentException("Le numéro de téléphone existe déjà");
            }
            user.setPhoneNumber(request.phoneNumber());
        }
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw new IllegalArgumentException("Le mot de passe doit contenir au moins 8 caractères");
            }
            user.setPassword(passwordEncoder.encode(request.password()));
        }

        return usersDTOMapper.apply(usersRepository.save(user));
    }

    public void deleteUser(UUID id) {
        Users user = findUserById(id);
        usersRepository.delete(user);
    }

    private Users findUserById(UUID id) {
        return usersRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
    }

    private void validateCreateRequest(UsersDTO request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("Le nom d'utilisateur est obligatoire");
        }
        if (!isValidEmail(request.email())) {
            throw new IllegalArgumentException("Adresse email invalide");
        }
        if (request.password() == null || request.password().length() < 8) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins 8 caractères");
        }
        if (!isValidInternationalPhone(request.phoneNumber())) {
            throw new IllegalArgumentException("Numéro de téléphone invalide");
        }

    }

    private boolean isValidInternationalPhone(String phone) {
        return phone != null && phone.matches("^\\+[1-9]\\d{7,14}$");
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }
}
