package com.b2la.antiplagiat.controller;

import com.b2la.antiplagiat.dto.ApiResponse;
import com.b2la.antiplagiat.dto.AuthResponseDTO;
import com.b2la.antiplagiat.dto.LoginRequestDTO;
import com.b2la.antiplagiat.dto.TwoFactorStartResponseDTO;
import com.b2la.antiplagiat.dto.TwoFactorVerifyDTO;
import com.b2la.antiplagiat.dto.UsersDTO;
import com.b2la.antiplagiat.dto.UsersResponseDTO;
import com.b2la.antiplagiat.service.AuthService;
import com.b2la.antiplagiat.service.UsersService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UsersController {

    private final UsersService usersService;
    private final AuthService authService;

    public UsersController(UsersService usersService, AuthService authService) {
        this.usersService = usersService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UsersResponseDTO>> createUser(@RequestBody UsersDTO request) {
        UsersResponseDTO user = usersService.addUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("success", "Utilisateur créé", user));
    }

    @PostMapping("/login")
    public ApiResponse<TwoFactorStartResponseDTO> login(@RequestBody LoginRequestDTO request) {
        return new ApiResponse<>("success", "Code de vérification envoyé par mail", authService.startLogin(request));
    }

    @PostMapping("/login/verify")
    public ApiResponse<AuthResponseDTO> verifyLogin(@RequestBody TwoFactorVerifyDTO request) {
        return new ApiResponse<>("success", "Connexion réussie", authService.verifyLogin(request));
    }

    @GetMapping
    public ApiResponse<List<UsersResponseDTO>> getAllUsers() {
        return new ApiResponse<>("success", usersService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ApiResponse<UsersResponseDTO> getUserById(@PathVariable UUID id) {
        return new ApiResponse<>("success", usersService.getUserById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<UsersResponseDTO> updateUser(@PathVariable UUID id, @RequestBody UsersDTO request) {
        return new ApiResponse<>("success", "Utilisateur modifié", usersService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable UUID id) {
        usersService.deleteUser(id);
        return ResponseEntity.ok(new ApiResponse<>("success", "Utilisateur supprimé"));
    }
}
