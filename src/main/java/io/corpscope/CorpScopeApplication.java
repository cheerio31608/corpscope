package io.corpscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CorpScopeApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(CorpScopeApplication.class, args);
        if ("none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type"))) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
