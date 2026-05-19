package com.costacloud.contractmanagement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Contract Management API")
                        .version("1.0.0")
                        .description("REST API documentation for the Contract Management System. Handles file uploads to MinIO and tracking via MongoDB.")
                        .contact(new Contact()
                                .name("CostaCloud Team")
                                .email("support@costacloud.com")));
    }
}
