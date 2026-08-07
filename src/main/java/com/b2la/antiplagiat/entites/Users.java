package com.b2la.antiplagiat.entites;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Users {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private UUID id;
    @Column(nullable = false, unique = true)
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @Column(nullable = false, unique = true)
    private String phoneNumber;
    private String firstName;
    private String lastName;
    private String surname;
    @OneToOne(cascade = CascadeType.ALL)
    private Roles role;
    private LocalDate dateOfBirth;
    @Column(nullable = false)
    private LocalDateTime createdAt;




}
