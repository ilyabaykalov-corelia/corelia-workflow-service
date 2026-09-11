package ru.corelia.workflow;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.cache.UserCache;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.integration.BpmClient;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** Нормализует представление исполнителя и названия ролей Platform V. */
@Component
public class TaskPresentation {
    private final BpmClient bpm;
    private final CoreliaConfig config;
    private final UserCache cache;

    public TaskPresentation(BpmClient bpm, CoreliaConfig config, UserCache cache) {
        this.bpm = bpm;
        this.config = config;
        this.cache = cache;
    }

    public static String attribute(JsonNode task, String key) {
        return comparable(task.path("attributes").path(key));
    }

    public static String login(JsonNode task) {
        return fallback(
                firstComparable(task, "assignee", "assigneeLogin", "executorLogin", "performerLogin"),
                fallback(actorLogin(task.path("executor")), actorLogin(task.path("performer"))));
    }

    private static String firstComparable(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = comparable(node.path(field));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    public static String name(JsonNode task) {
        return fallback(
                first(task, "assigneeName", "executorName", "performerName"),
                fallback(
                        actorName(task.path("executor")),
                        fallback(actorName(task.path("performer")), login(task))));
    }

    public static String role(JsonNode task) {
        String direct = text(task, "executorRole");
        if (!direct.isEmpty()) return direct;
        String roles = list(task.path("executorRoles")).stream()
                .map(ru.corelia.support.Json::text)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
        if (!roles.isEmpty()) return roles;
        roles = first(task, "role", "group", "candidateGroup");
        if (!roles.isEmpty()) return roles;
        JsonNode groups = task.path("candidateGroups");
        if (groups.isTextual()) return text(groups);
        return list(groups).stream()
                .map(ru.corelia.support.Json::text)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
    }

    private static String actorLogin(JsonNode actor) {
        return actor.isTextual()
                ? text(actor)
                : firstComparable(actor, "login", "username", "userName");
    }

    private static String actorName(JsonNode actor) {
        return actor.isTextual()
                ? text(actor)
                : fallback(
                        first(actor, "fullName", "displayName", "label", "name"),
                        actorLogin(actor));
    }

    public static String title(JsonNode task) {
        return (text(task, "title") + " " + text(task, "type") + " " + role(task))
                .toLowerCase(Locale.ROOT);
    }

    public static String searchText(JsonNode task) {
        StringBuilder text =
                new StringBuilder(
                        text(task, "type")
                                + " "
                                + text(task, "title")
                                + " "
                                + text(task, "description"));
        task.path("attributes")
                .properties()
                .forEach(entry -> text.append(' ').append(comparable(entry.getValue())));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    public JsonNode executor(JsonNode task, AuthContext auth) {
        if (task == null) return null;
        String role = role(task), login = login(task), name = name(task);
        var result =
                object(
                        "login",
                        login.isEmpty() ? null : login,
                        "name",
                        name.isEmpty() ? null : name,
                        "role",
                        role.isEmpty() ? null : role,
                        "roleLabel",
                        roleLabel(role, auth));
        if (task.has("status")) result.set("taskStatus", task.path("status"));
        if (task.has("title") || task.has("type"))
            result.set(
                    "taskTitle", task.hasNonNull("title") ? task.path("title") : task.path("type"));
        return result;
    }

    public String roleLabel(String roles, AuthContext auth) {
        List<String> codes =
                Arrays.stream(roles.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        if (codes.isEmpty()) return null;
        JsonNode labels =
                "true".equals(config.value("PLATFORM_V_ROLE_LABELS_ENABLED"))
                        ? cache.load(
                                auth,
                                "role-labels",
                                config.number("PLATFORM_V_ROLE_LABELS_CACHE_TTL_MS", 300000),
                                () -> loadLabels(auth))
                        : object();
        return String.join(
                ", ", codes.stream().map(code -> fallback(text(labels, code), code)).toList());
    }

    private JsonNode loadLabels(AuthContext auth) {
        String configured = config.value("PLATFORM_V_ROLE_LABELS_PATH").trim();
        List<String> paths =
                configured.isEmpty()
                        ? List.of("/system/v1/groups/roles", "/groups/roles")
                        : List.of(configured);
        for (String path : paths) {
            try {
                var labels = object();
                appendLabels(bpm.taskList(path, null, Map.of(), auth), labels);
                return labels;
            } catch (ApiException error) {
                if (!Set.of(403, 404, 405).contains(error.status())) throw error;
            }
        }
        return object();
    }

    private void appendLabels(JsonNode payload, tools.jackson.databind.node.ObjectNode labels) {
        if (payload.isArray()) {
            payload.forEach(item -> appendLabels(item, labels));
            return;
        }
        if (!payload.isObject()) return;
        String name = first(payload, "name", "code", "role", "value", "id");
        String label =
                fallback(first(payload, "label", "title", "displayName", "description"), name);
        if (!name.isEmpty() && !label.isEmpty()) labels.put(name, label);
        for (String key : List.of("roles", "items", "content", "data", "result"))
            if (payload.has(key)) appendLabels(payload.path(key), labels);
    }
}
