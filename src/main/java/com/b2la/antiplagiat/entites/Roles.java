package com.b2la.antiplagiat.entites;

import com.b2la.antiplagiat.enumerote.Role;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="roles")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Roles {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Enumerated(EnumType.STRING)
    private Role libelle;
}
