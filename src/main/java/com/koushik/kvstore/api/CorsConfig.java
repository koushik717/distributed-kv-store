package com.koushik.kvstore.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(
                "http://localhost:5173",
                "https://kv-store-frontend.vercel.app"
            )
            .allowedMethods("GET", "PUT", "DELETE", "POST", "OPTIONS")
            .allowedHeaders("*");
    }
}
