package ru.corelia.workflow;

import static ru.corelia.support.Json.*;
import static ru.corelia.workflow.TaskPresentation.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.http.ApiException;
import ru.corelia.integration.*;
import ru.corelia.support.LogJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

/** Сценарии процессов и задач. Связь процесса с видом документа читает только из платформы. */
@Service
public class WorkflowService {
    private final BpmClient bpm;
    private final DataSpaceClient data;
    private final TaskGateway tasks;
    private final CoreliaConfig config;

    public WorkflowService(
            BpmClient bpm, DataSpaceClient data, TaskGateway tasks, CoreliaConfig config) {
        this.bpm = bpm;
        this.data = data;
        this.tasks = tasks;
        this.config = config;
    }

    public JsonNode process(String id, AuthContext auth) {
        JsonNode result = bpm.process("/instances/" + encode(id), null, auth);
        assertNoIncident(result);
        return result;
    }

    public JsonNode create(JsonNode body, AuthContext auth) {
        String type = text(body, "typeCode"), id = text(body, "documentId");
        if (id.isEmpty()) throw new ApiException(400, "Не задан идентификатор документа");
        PdsContract.requireType(type);
        JsonNode attributes = PdsContract.validateAttributes(body.path("attributes"), false);
        ObjectNode payload = copy(attributes);
        payload.put("tenant", config.tenant())
                .put("appInstanceId", config.appId())
                .put("documentId", id)
                .put("documentType", type)
                .put("createdBy", auth.login())
                .put("createdAt", Instant.now().toString());
        ObjectNode external =
                object(
                        "documentId",
                        id,
                        "documentType",
                        type,
                        "tenant",
                        config.tenant(),
                        "appInstanceId",
                        config.appId());
        // Номер договора входит в контракт поиска задач текущего процесса ПДС.
        external.set("contractNumber", attributes.path("contractNumber"));
        JsonNode result =
                bpm.process(
                        "/processes/" + encode(processId(type, auth)) + ":start",
                        object("businessKey", id, "payload", payload, "externalIds", external),
                        auth);
        assertNoIncident(result);
        return result;
    }

    private String processId(String type, AuthContext auth) {
        for (int offset = 0; offset < 10000; ) {
            JsonNode page =
                    data.query(
                                    "searchDocumentProcessSettings",
                                    object("offset", offset, "limit", 500),
                                    auth)
                            .path("searchDocumentProcessSettings");
            List<JsonNode> rows = list(page.path("elems"));
            for (JsonNode row : rows)
                if (!row.path("enabled").equals(MAPPER.getNodeFactory().booleanNode(false))
                        && type.equals(text(row.path("documentType"), "id"))) {
                    String id = text(row, "processId");
                    if (id.isEmpty())
                        throw new ApiException(
                                400, "Для вида документа " + type + " не задан процесс создания");
                    return id;
                }
            offset += rows.size();
            if (rows.isEmpty() || offset >= number(page, "count", offset)) break;
        }
        throw new ApiException(
                400, "Для вида документа " + type + " не настроен активный процесс создания");
    }

    public JsonNode requireTask(String id, AuthContext auth) {
        JsonNode task = tasks.find(id, auth);
        if (task == null) throw new ApiException(404, "Активная задача не найдена");
        return task;
    }

    private String type(JsonNode task) {
        String type = fallback(attribute(task, "documentType"), PdsContract.TYPE);
        PdsContract.requireType(type);
        return type;
    }

    public List<JsonNode> actions(JsonNode task, AuthContext auth) {
        JsonNode detail = tasks.details(task, auth);
        if (!text(detail, "formType").equals("COMPLETIONS")) return List.of();
        type(task);
        List<JsonNode> result = new ArrayList<>();
        int index = 0;
        for (JsonNode option : list(detail.path("completions").path("options"))) {
            index++;
            JsonNode parameters = option.path("result");
            if (!parameters.isObject() || parameters.isEmpty()) continue;
            String status = PdsContract.status(text(parameters, "approvalStatus"));
            if (Objects.equals(status, PdsContract.INITIAL_STATUS)) status = null;
            String code =
                    status == null
                            ? fallback(text(option, "label"), "completion_" + index)
                            : status;
            ObjectNode action =
                    object(
                            "code",
                            code,
                            "label",
                            fallback(text(option, "label"), code),
                            "tone",
                            status == null ? "success" : PdsContract.tone(status),
                            "result",
                            parameters);
            if (status != null) action.put("status", status);
            result.add(action);
        }
        return result;
    }

    public JsonNode start(String id, AuthContext auth) {
        requireTask(id, auth);
        JsonNode result = bpm.system("/system/v6/usertasks:start", clientFields(id, auth), auth);
        assertSuccess(result, id);
        return result;
    }

    public JsonNode complete(String id, JsonNode payload, AuthContext auth) {
        JsonNode task = requireTask(id, auth);
        JsonNode parameters = payload.path("parameters");
        String code = first(payload, "actionCode", "approvalStatus", "decision");
        JsonNode selected =
                actions(task, auth).stream()
                        .filter(
                                a ->
                                        (!code.isEmpty() && code.equalsIgnoreCase(text(a, "code")))
                                                || (parameters.isObject()
                                                        && matchesParameters(
                                                                parameters,
                                                                a.path("result"),
                                                                auth)))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                400, "Действие недоступно для текущей задачи"));
        if (!text(task, "status").equals("STARTED")) start(id, auth);
        ObjectNode completion = copy(selected.path("result"));
        if (List.of("взять в работу", "take_on")
                        .contains(text(selected, "label").toLowerCase(Locale.ROOT))
                || text(selected, "code").equalsIgnoreCase("take_on"))
            completion.put("assignee", auth.login());
        ObjectNode body = clientFields(id, auth);
        body.set("parameters", completion);
        JsonNode result = bpm.system("/system/v6/usertasks:complete", body, auth);
        assertSuccess(result, id);
        return result;
    }

    private boolean matchesParameters(JsonNode parameters, JsonNode result, AuthContext auth) {
        ObjectNode comparable = copy(parameters);
        if (auth.login().equals(text(comparable, "assignee")) && !result.has("assignee"))
            comparable.remove("assignee");
        return comparable.equals(result);
    }

    private static ObjectNode clientFields(String id, AuthContext auth) {
        return object(
                "clientLogin",
                auth.login(),
                "clientName",
                fallback(auth.fullName(), auth.login()),
                "userTaskIds",
                List.of(id));
    }

    public static void assertSuccess(JsonNode result, String id) {
        for (JsonNode operation : list(result.path("operationResults")))
            if (id.equals(text(operation, "userTaskId"))) {
                if ("SUCCESS".equals(text(operation, "responseType"))) return;
                throw new ApiException(
                        502, "Платформа не выполнила действие: " + text(operation, "responseType"));
            }
        if (list(result.path("successIds")).stream().anyMatch(n -> id.equals(text(n)))) return;
        if (result.has("successIds") || result.has("failedIds"))
            throw new ApiException(502, "Платформа не выполнила действие по задаче " + id);
    }

    private static void assertNoIncident(JsonNode instance) {
        JsonNode activity =
                list(instance.path("currentActivities")).stream()
                        .filter(n -> n.path("isIncident").asBoolean())
                        .findFirst()
                        .orElse(object());
        if (instance.path("isIncident").asBoolean() || !activity.isEmpty())
            LogJson.info(
                    "Platform V process incident",
                    object(
                            "processInstanceId", text(instance, "id"),
                            "state", text(instance, "state"),
                            "definitionId", first(activity, "definitionId", "name"),
                            "error", text(activity, "error")));
        if (instance.path("isIncident").asBoolean() || !activity.isEmpty())
            throw new ApiException(
                    502,
                    "Процесс Platform V запущен, но упал на "
                            + fallback(
                                    first(activity, "definitionId", "name"),
                                    "неизвестной активности")
                            + (text(activity, "error").isEmpty()
                                    ? ""
                                    : ": " + text(activity, "error")));
    }
}
