package ru.corelia.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import ru.corelia.config.LocalEnvironment;

/** Запускает приложение corelia-workflow-service. */
@SpringBootApplication(
        scanBasePackages = {
            "ru.corelia.config",
            "ru.corelia.profile",
            "ru.corelia.support",
            "ru.corelia.auth",
            "ru.corelia.http",
            "ru.corelia.cache",
            "ru.corelia.integration",
            "ru.corelia.transport",
            "ru.corelia.workflow"
        })
public class WorkflowApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(WorkflowApplication.class);
        app.setDefaultProperties(LocalEnvironment.load());
        app.run(args);
    }
}
