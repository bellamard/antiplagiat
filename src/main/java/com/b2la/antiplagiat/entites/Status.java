package com.b2la.antiplagiat.entites;

import com.b2la.antiplagiat.enumerote.StatusEnum;
import jakarta.persistence.*;
import lombok.*;



@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "status")
public class Status {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Enumerated(EnumType.STRING)
    @Column(name = "libelle", nullable = false)
    private StatusEnum libelle;
}
