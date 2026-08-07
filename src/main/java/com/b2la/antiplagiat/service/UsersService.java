package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.dto.UsersResponseDTO;
import com.b2la.antiplagiat.entites.Roles;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.enumerote.Role;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class UsersService {

    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    BCryptPasswordEncoder bCryptPasswordEncoder;

    public UsersResponseDTO addUser(Users user)throws Exception {
        Users userInDb= this.usersRepository.findByPhoneNumber(user.getPhoneNumber());
        if(userInDb==null){
            if (!isValidInternationalPhone(user.getPhoneNumber())) {
                throw new Exception("Invalid phone number");
            }

            if(user.getPassword().length()<8) {
                throw new Exception("Password too short");
            }
            String passCrypt=this.bCryptPasswordEncoder.encode(user.getPassword());
            user.setPassword(passCrypt);
            Roles userRole = new Roles();
            userRole.setLibelle(Role.STUDENT);
            user.setRole(userRole);
            Users newUser=this.usersRepository.save(user);
            return newUser;

        }else{
            throw new EntityNotFoundException("l'utilisateur existe déjà");
        }

    }

    private boolean isValidInternationalPhone(String phone) {
        return phone != null && phone.matches("^\\+[1-9]\\d{7,14}$");
    }
}
