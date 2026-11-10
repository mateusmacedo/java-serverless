package com.mmda.serverless.sample.functions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FunctionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FunctionsApplication.class, args);
    }
}
