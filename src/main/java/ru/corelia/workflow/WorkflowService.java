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
    private final TaskPresentation presentation;
    private final CoreliaConfig config;

    public WorkflowService(
            BpmClient bpm,
            DataSpaceClient data,
            TaskGateway tasks,
            TaskPresentation presentation,
            CoreliaConfig config) {
        this.bpm = bpm;
        this.data = data;
        this.tasks = tasks;
        this.presentation = presentation;
        this.config = config;
    }

    /** Возвращает современный контракт очереди задач Corelia. */
    public JsonNode search(JsonNode body, AuthContext auth) {
        String queue = text(body, "queue").toUpperCase(Locale.ROOT);
        String requestedStatus = text(body, "status").toUpperCase(Locale.ROOT);
        String query = text(body, "query").toLowerCase(Locale.ROOT);
        Set<String> statuses = Set.of("NEW", "ASSIGNED", "STARTED", "COMPLETED", "ABORTED");
        List<JsonNode> found;
        if (queue.equals("MY") || queue.equals("AVAILABLE")) {
            List<String> selected =
                    statuses.contains(requestedStatus)
                            ? List.of(requestedStatus)
                            : List.of("NEW", "ASSIGNED", "STARTED");
            found =
                    tasks.searchStatuses(selected, TaskGateway.SCOPES, object(), auth).stream()
                            .filter(
                                    task ->
                                            queue.equals("MY")
                                                    ? auth.login().equalsIgnoreCase(assignedLogin(task))
                                                    : !assigned(task))
                            .toList();
        } else {
            ObjectNode filters = object();
            if (statuses.contains(requestedStatus))
                filters.set("status", object("value", requestedStatus));
            found = tasks.broad(filters, auth);
        }
        List<JsonNode> visible =
                found.stream()
                        .filter(task -> query.isEmpty() || searchText(task).contains(query))
                        .map(task -> decorateTask(task, auth))
                        .toList();
        LogJson.info(
                "Список задач сформирован",
                object(
                        "queue", queue.isEmpty() ? "ALL" : queue,
                        "requestedStatus", requestedStatus,
                        "found", found.size(),
                        "visible", visible.size()));
        return object("items", visible, "total", visible.size());
    }

    private static boolean assigned(JsonNode task) {
        for (String field : List.of("assignee", "assigneeLogin", "executorLogin", "performerLogin"))
            if (!comparable(task.path(field)).isEmpty()) return true;
        for (String field : List.of("executor", "performer")) {
            JsonNode actor = task.path(field);
            if (actor.isObject()
                    && !comparable(actor.path("login")).isEmpty()) return true;
        }
        return !attribute(task, "assignee").isEmpty();
    }

    private static String assignedLogin(JsonNode task) {
        String login = login(task);
        if (!login.isEmpty()) return login;
        return attribute(task, "assignee");
    }

    public JsonNode summary(AuthContext auth) {
        return object(
                "my", number(search(object("queue", "MY"), auth), "total", 0),
                "available", number(search(object("queue", "AVAILABLE"), auth), "total", 0));
    }

    /** Контекст процесса для карточки: активная задача, действия и исполнитель. */
    public JsonNode documentWorkflow(String type, String documentId, AuthContext auth) {
        PdsContract.requireType(type);
        JsonNode task = null;
        // После завершения задачи BPMU некоторое время может отдавать старое
        // состояние. Повторяем чтение, чтобы карточка сразу показывала новый
        // исполнитель и доступные действия.
        for (int attempt = 0; attempt < 3 && task == null; attempt++) {
            task = tasks.byDocument(documentId, auth).stream()
                    .min(Comparator.comparingInt(value -> switch (text(value, "status")) {
                        case "STARTED" -> 0;
                        case "ASSIGNED" -> 1;
                        case "NEW" -> 2;
                        default -> 3;
                    }))
                    .orElse(null);
            if (task == null && attempt < 2) pause(350);
        }
        if (task == null) return object("task", null, "availableActions", List.of(), "executor", null);
        return object(
                "task", task,
                "availableActions", actions(task, auth),
                "executor", presentation.executor(task, auth));
    }

    private JsonNode decorateTask(JsonNode task, AuthContext auth) {
        ObjectNode result = copy(task);
        result.set("availableActions", array(actions(task, auth)));
        return result;
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
        if (takeInWork(selected))
            completion.put("assignee", auth.login());
        ObjectNode body = clientFields(id, auth);
        body.set("parameters", completion);
        JsonNode result = bpm.system("/system/v6/usertasks:complete", body, auth);
        assertSuccess(result, id);
        String documentId = attribute(task, "documentId");
        awaitDocumentStatus(documentId, text(completion, "approvalStatus"), auth);
        if (takeInWork(selected)) startFollowUp(documentId, id, auth);
        return result;
    }

    private void startFollowUp(String documentId, String completedTaskId, AuthContext auth) {
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonNode next =
                    tasks.byDocument(documentId, auth).stream()
                            .filter(task -> !completedTaskId.equals(text(task, "id")))
                            .filter(task -> Set.of("NEW", "ASSIGNED").contains(text(task, "status")))
                            .findFirst()
                            .orElse(null);
            if (next != null) {
                JsonNode started = bpm.system("/system/v6/usertasks:start", clientFields(text(next, "id"), auth), auth);
                assertSuccess(started, text(next, "id"));
                return;
            }
            if (attempt < 19) pause(250);
        }
    }

    private static boolean takeInWork(JsonNode action) {
        return Set.of("взять в работу", "take_on")
                        .contains(text(action, "label").toLowerCase(Locale.ROOT))
                || text(action, "code").equalsIgnoreCase("take_on")
                || text(action.path("result"), "approvalStatus").equalsIgnoreCase("IN_WORK");
    }

    /** Ждёт, пока асинхронное обновление карточки из БП станет видимо в DataSpace. */
    private void awaitDocumentStatus(String documentId, String expected, AuthContext auth) {
        if (documentId.isEmpty() || expected.isEmpty()) return;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonNode page =
                    data.query("searchPdsContract", object("offset", 0, "limit", 500), auth)
                            .path("searchPdsContract");
            boolean matched =
                    list(page.path("elems")).stream()
                            .filter(row -> documentId.equals(text(row, "documentId")))
                            .anyMatch(row -> expected.equals(PdsContract.normalizeStatus(text(row, "approvalStatus"))));
            if (matched) return;
            if (attempt < 19) pause(250);
        }
        LogJson.info(
                "Platform V document status was not updated in time",
                object("documentId", documentId, "expectedStatus", expected));
        throw new ApiException(
                502,
                "Platform V не обновила статус карточки документа "
                        + documentId
                        + " на "
                        + expected
                        + " после завершения задачи");
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Ожидание платформы прервано");
        }
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
