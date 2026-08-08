package com.b2la.antiplagiat.entites;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "status")
public class Status {
    @Id
    @GeneratedValue
    private int id;
    @Enumerated(EnumType.STRING)
    private String libelle;
}
