package ru.corelia.workflow;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;

import tools.jackson.databind.JsonNode;

/** Внутренние операции процессов и задач; произвольные URL платформы не принимаются. */
@RestController
@RequestMapping("/internal/v1")
public class WorkflowController {
    private final WorkflowService workflow;
    private final TaskGateway tasks;
    private final TaskPresentation presentation;
    private final ApiRequest requests;

    public WorkflowController(
            WorkflowService workflow,
            TaskGateway tasks,
            TaskPresentation presentation,
            ApiRequest requests) {
        this.workflow = workflow;
        this.tasks = tasks;
        this.presentation = presentation;
        this.requests = requests;
    }

    @PostMapping("/processes/start")
    public JsonNode create(HttpServletRequest r) {
        return workflow.create(requests.body(r), requests.auth(r));
    }

    @GetMapping("/processes/{id}")
    public JsonNode process(@PathVariable String id, HttpServletRequest r) {
        return workflow.process(id, requests.auth(r));
    }

    @PostMapping("/tasks/search")
    public JsonNode search(HttpServletRequest r) {
        JsonNode body = requests.body(r);
        String scope = text(body, "scope");
        return tasks.search(body.path("filters"), scope.isEmpty() ? null : scope, requests.auth(r));
    }

    @GetMapping("/tasks/{id}")
    public JsonNode task(@PathVariable String id, HttpServletRequest r) {
        return workflow.requireTask(id, requests.auth(r));
    }

    @GetMapping("/tasks/{id}/details")
    public JsonNode details(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        return tasks.details(workflow.requireTask(id, auth), auth);
    }

    @GetMapping("/tasks/{id}/actions")
    public JsonNode actions(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        return array(workflow.actions(workflow.requireTask(id, auth), auth));
    }

    @PostMapping("/tasks/{id}/start")
    public JsonNode start(@PathVariable String id, HttpServletRequest r) {
        return workflow.start(id, requests.auth(r));
    }

    @PostMapping("/tasks/{id}/complete")
    public JsonNode complete(@PathVariable String id, HttpServletRequest r) {
        return workflow.complete(id, requests.body(r), requests.auth(r));
    }

    @GetMapping("/roles/{role}")
    public JsonNode role(@PathVariable String role, HttpServletRequest r) {
        return object("label", presentation.roleLabel(role, requests.auth(r)));
    }
}
