package ru.corelia.workflow;

import static ru.corelia.support.Json.*;
import static ru.corelia.workflow.TaskPresentation.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.cache.UserCache;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.integration.BpmClient;
import ru.corelia.support.LogJson;
import ru.corelia.support.ParallelCalls;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** Поиск и чтение задач через адаптеры BPMU/BPMX Platform V. */
@Component
public class TaskGateway {
    public static final List<String> ACTIVE = List.of("STARTED", "ASSIGNED", "NEW");
    public static final List<String> SCOPES = List.of("EXECUTOR", "MANAGER");
    private final BpmClient bpm;
    private final UserCache cache;
    private final CoreliaConfig config;
    private final ParallelCalls parallel;

    public TaskGateway(
            BpmClient bpm, UserCache cache, CoreliaConfig config, ParallelCalls parallel) {
        this.bpm = bpm;
        this.cache = cache;
        this.config = config;
        this.parallel = parallel;
    }

    public JsonNode search(JsonNode filters, String scope, AuthContext auth) {
        Map<String, Object> query =
                new LinkedHashMap<>(Map.of("attributes", "*", "limit", 100, "offset", 0));
        if (scope != null) query.put("scope", scope);
        return bpm.taskList("/system/v2/tasks:search", filters, query, auth);
    }

    public List<JsonNode> searchStatuses(
            List<String> statuses, List<String> scopes, JsonNode filters, AuthContext auth) {
        record Search(String status, String scope) {}
        List<Search> searches =
                statuses.stream()
                        .flatMap(status -> scopes.stream().map(scope -> new Search(status, scope)))
                        .toList();
        List<JsonNode> responses =
                parallel.map(
                        searches,
                        item -> {
                            var filter = copy(filters);
                            filter.set("status", object("value", item.status()));
                            return search(filter, item.scope(), auth);
                        });
        return unique(
                responses.stream()
                        .flatMap(response -> responseItems(response).stream())
                        .toList());
    }

    public List<JsonNode> broad(JsonNode filters, AuthContext auth) {
        List<JsonNode> responses = parallel.map(SCOPES, scope -> search(filters, scope, auth));
        return unique(
                responses.stream()
                .flatMap(response -> responseItems(response).stream())
                        .toList());
    }

    public List<JsonNode> byDocument(String id, AuthContext auth) {
        return broad(
                        object(
                                "attributes",
                                object("documentId", object("value", id, "exact", true))),
                        auth)
                .stream()
                .filter(
                        task ->
                                ACTIVE.contains(text(task, "status"))
                                        && id.equals(attribute(task, "documentId")))
                .toList();
    }

    public JsonNode find(String id, AuthContext auth) {
        try {
            return bpm.taskList("/system/v1/user-tasks/" + encode(id), null, Map.of(), auth);
        } catch (ApiException error) {
            if (!BpmClient.unavailable(error)) throw error;
            LogJson.info(
                    "BPMU Task List task detail was not available",
                    object(
                            "taskId", id,
                            "status", error.status(),
                            "message", error.getMessage()));
        }
        return searchStatuses(ACTIVE, SCOPES, object(), auth).stream()
                .filter(task -> id.equals(text(task, "id")))
                .findFirst()
                .orElse(null);
    }

    public JsonNode details(JsonNode task, AuthContext auth) {
        if (text(task, "formType").equals("COMPLETIONS")
                && task.path("completions").path("options").isArray()) return task;
        String id = encode(text(task, "id"));
        try {
            return bpm.system("/system/v6/usertasks/" + id, null, auth);
        } catch (ApiException error) {
            if (!BpmClient.unavailable(error)) throw error;
            LogJson.info(
                    "BPMX usertask detail was not available, falling back to BPMU detail",
                    object(
                            "taskId", text(task, "id"),
                            "status", error.status(),
                            "message", error.getMessage()));
        }
        try {
            return bpm.taskList("/system/v1/user-tasks/" + id, null, Map.of(), auth);
        } catch (ApiException error) {
            if (!BpmClient.unavailable(error)) throw error;
            LogJson.info(
                    "BPMU Task List task detail was not available",
                    object(
                            "taskId", text(task, "id"),
                            "status", error.status(),
                            "message", error.getMessage()));
        }
        return task;
    }

    private static List<JsonNode> unique(List<JsonNode> tasks) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        tasks.forEach(task -> result.putIfAbsent(text(task, "id"), task));
        return new ArrayList<>(result.values());
    }

    private static List<JsonNode> responseItems(JsonNode response) {
        if (response == null || response.isNull()) return List.of();
        if (response.isArray()) return list(response);
        for (String key : List.of("items", "content", "data", "tasks", "result")) {
            JsonNode value = response.path(key);
            if (value.isArray()) return list(value);
            if (value.isObject()) {
                List<JsonNode> nested = responseItems(value);
                if (!nested.isEmpty()) return nested;
            }
        }
        return List.of();
    }
}
