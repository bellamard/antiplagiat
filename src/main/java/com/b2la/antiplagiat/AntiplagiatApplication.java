package com.b2la.antiplagiat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AntiplagiatApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntiplagiatApplication.class, args);
    }

}
