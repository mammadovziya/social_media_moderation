package com.example.moderation.gateway;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Social Media Moderation API",
                        version = "v1",
                        description = "Checks posts, comments and usernames."),
        tags =
                @Tag(
                        name = "Moderation",
                        description = "Public moderation API"))
public class OpenApiConfiguration {}
