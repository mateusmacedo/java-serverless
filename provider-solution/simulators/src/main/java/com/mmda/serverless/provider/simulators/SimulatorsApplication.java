package com.mmda.serverless.provider.simulators;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorsApplication.class, args);
    }
}
