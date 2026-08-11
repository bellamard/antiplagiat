package com.b2la.antiplagiat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "seeder")
@Getter
@Setter
public class SeedProperties {
    private List<String> roles = new ArrayList<>();
    private List<UserSeed> users = new ArrayList<>();

    @Getter
    @Setter
    public static class UserSeed {
        private String username;
        private String email;
        private String phoneNumber;
        private String password;
        private String role;
        private String firstName;
        private String lastName;
    }
}
