package com.b2la.antiplagiat.mapper;

import com.b2la.antiplagiat.dto.UsersResponseDTO;
import com.b2la.antiplagiat.entites.Users;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class UsersDTOMapper implements Function<Users, UsersResponseDTO> {
    @Override
    public UsersResponseDTO apply(Users user) {
        return new UsersResponseDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getSurname(),
                user.getPhoneNumber(),
                user.getRole().getLibelle(),
                user.getDateOfBirth(),
                user.getCreatedAt()
        );
    }
}
