package ru.corelia.workflow;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;
import ru.corelia.observability.CoreliaObservability;

import tools.jackson.databind.JsonNode;

/** Внутренние операции процессов и задач; произвольные URL платформы не принимаются. */
@RestController
@RequestMapping("/internal/v1")
public class WorkflowController {
    private final WorkflowService workflow;
    private final ApiRequest requests;
    private final CoreliaObservability observability;

    public WorkflowController(
            WorkflowService workflow,
            ApiRequest requests,
            CoreliaObservability observability) {
        this.workflow = workflow;
        this.requests = requests;
        this.observability = observability;
    }

    @PostMapping("/processes/start")
    public JsonNode create(HttpServletRequest r) {
        try {
            JsonNode result = observability.observe(
                    "workflow.start", () -> workflow.create(requests.body(r), requests.auth(r)));
            observability.workflowStarted();
            return result;
        } catch (RuntimeException error) {
            observability.workflowStartFailed();
            throw error;
        }
    }

    @GetMapping("/processes/{id}")
    public JsonNode process(@PathVariable String id, HttpServletRequest r) {
        return workflow.process(id, requests.auth(r));
    }

    @PostMapping("/tasks/search")
    public JsonNode search(HttpServletRequest r) {
        return workflow.search(requests.body(r), requests.auth(r));
    }

    @GetMapping("/documents/{type}/{id}/workflow")
    public JsonNode documentWorkflow(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return workflow.documentWorkflow(type, id, requests.auth(r));
    }

    @GetMapping("/tasks/summary")
    public JsonNode summary(HttpServletRequest r) {
        return workflow.summary(requests.auth(r));
    }

    @GetMapping("/tasks/{id}")
    public JsonNode task(@PathVariable String id, HttpServletRequest r) {
        return workflow.requireTask(id, requests.auth(r));
    }

    @GetMapping("/tasks/{id}/details")
    public JsonNode details(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        return workflow.requireTask(id, auth);
    }

    @GetMapping("/tasks/{id}/actions")
    public JsonNode actions(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        return workflow.actions(workflow.taskModel(id, auth));
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
        return object("label", workflow.roleLabel(role, requests.auth(r)));
    }
}
