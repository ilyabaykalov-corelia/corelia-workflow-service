package ru.corelia.workflow;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.TaskProvider;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Сценарии Corelia для процессов и задач без знания provider transport. */
@Service
public class WorkflowService {
    private final DocumentTypeCatalog types; private final DocumentStore documents; private final DocumentVersionStore versions; private final WorkflowProvider workflows; private final TaskProvider tasks;
    public WorkflowService(DocumentTypeCatalog types, DocumentStore documents, DocumentVersionStore versions, WorkflowProvider workflows, TaskProvider tasks) { this.types = types; this.documents = documents; this.versions = versions; this.workflows = workflows; this.tasks = tasks; }
    public JsonNode search(JsonNode body, AuthContext auth) {
        String queue = text(body, "queue").toUpperCase(Locale.ROOT), requested = text(body, "status").toUpperCase(Locale.ROOT), query = text(body, "query").toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("NEW", "ASSIGNED", "STARTED", "COMPLETED", "ABORTED"); Set<String> statuses = allowed.contains(requested) ? Set.of(requested) : Set.of("NEW", "ASSIGNED", "STARTED");
        List<WorkflowTask> found = tasks.search(new TaskSearchRequest(statuses), auth).stream().filter(task -> queue.equals("MY") ? auth.login().equalsIgnoreCase(task.assignee()) : !queue.equals("AVAILABLE") || task.assignee().isEmpty()).filter(task -> query.isEmpty() || taskText(task).contains(query)).toList();
        return object("items", found.stream().map(task -> task(task, auth)).toList(), "total", found.size());
    }
    public JsonNode summary(AuthContext auth) { return object("my", number(search(object("queue", "MY"), auth), "total", 0), "available", number(search(object("queue", "AVAILABLE"), auth), "total", 0)); }
    public JsonNode documentWorkflow(String type, String documentId, AuthContext auth) {
        types.requireType(type); WorkflowTask task = tasks.findByDocument(documentId, auth).stream().min(Comparator.comparingInt(value -> priority(value.status()))).orElse(null);
        return task == null ? object("task", null, "availableActions", List.of(), "executor", null) : object("task", task(task, auth), "availableActions", actions(task), "executor", executor(task, auth));
    }
    public JsonNode process(String id, AuthContext auth) { return process(workflows.process(id, auth)); }
    public JsonNode create(JsonNode body, AuthContext auth) {
        String type = text(body, "typeCode"), id = text(body, "documentId"); if (id.isEmpty()) throw new ApiException(400, "Не задан идентификатор документа"); types.requireType(type);
        JsonNode attrs = types.validate(type, body.path("attributes"), false);
        documents.get(type, id, auth);
        if (types.initialAttachmentRequired(type) && versions.attachments(id, auth).stream().noneMatch(AttachmentMetadata::current))
            throw new ApiException(409, "Обязательное вложение ещё не зафиксировано");
        WorkflowTask existing = tasks.findByDocument(id, auth).stream().findFirst().orElse(null);
        if (existing != null) return object("id", "", "documentId", id, "state", "ACTIVE");
        return process(workflows.start(new WorkflowContext(id, type, map(attrs), auth.login(), id, null, text(body, "creationKey"), text(body, "creationHash")), auth));
    }
    public JsonNode requireTask(String id, AuthContext auth) { WorkflowTask task = tasks.task(id, auth); if (task == null) throw new ApiException(404, "Активная задача не найдена"); return task(task, auth); }
    WorkflowTask taskModel(String id, AuthContext auth) { WorkflowTask task = tasks.task(id, auth); if (task == null) throw new ApiException(404, "Активная задача не найдена"); return task; }
    public JsonNode actions(WorkflowTask task) { return array(task.actions().stream().map(this::action).toList()); }
    public JsonNode start(String id, AuthContext auth) { tasks.start(id, auth); return object("operationResults", List.of(object("userTaskId", id, "responseType", "SUCCESS"))); }
    public JsonNode complete(String id, JsonNode payload, AuthContext auth) {
        WorkflowTask task = taskModel(id, auth); String code = first(payload, "actionCode", "approvalStatus", "decision"); JsonNode supplied = payload.path("parameters");
        WorkflowAction selected = task.actions().stream().filter(action -> !code.isEmpty() && code.equalsIgnoreCase(action.code()) || supplied.isObject() && supplied.equals(attributes(action.parameters()))).findFirst().orElseThrow(() -> new ApiException(400, "Действие недоступно для текущей задачи"));
        if (!"STARTED".equals(task.status())) tasks.start(id, auth); var parameters = new LinkedHashMap<>(selected.parameters()); JsonNode completion = types.definition(type(task, auth)).workflow().path("completion"); boolean assigned = takeInWork(selected, completion); if (assigned) parameters.put(text(completion, "assigneeField"), MAPPER.getNodeFactory().textNode(auth.login())); tasks.complete(id, parameters, auth); if (assigned && completion.path("autoStart").asBoolean()) tasks.findByDocument(task.documentId(), auth).stream().filter(next -> !id.equals(next.id())).filter(next -> Set.of("NEW", "ASSIGNED").contains(next.status())).findFirst().ifPresent(next -> tasks.start(next.id(), auth)); return object("operationResults", List.of(object("userTaskId", id, "responseType", "SUCCESS")));
    }
    public String roleLabel(String role, AuthContext auth) { return tasks.roleLabel(role, auth); }
    private String type(WorkflowTask task, AuthContext auth) { String type = task.documentType(); if (type.isEmpty()) type = types.types().stream().filter(value -> { try { return documents.get(value, task.documentId(), auth) != null; } catch (ApiException ignored) { return false; }}).findFirst().orElseThrow(() -> new ApiException(404, "Документ задачи недоступен")); types.requireType(type); return type; }
    private static int priority(String status) { return switch (status) { case "STARTED" -> 0; case "ASSIGNED" -> 1; case "NEW" -> 2; default -> 3; }; }
    private static boolean takeInWork(WorkflowAction action, JsonNode rules) { return list(rules.path("assignmentStatuses")).stream().anyMatch(value -> text(value).equalsIgnoreCase(action.status())) || list(rules.path("assignmentCodes")).stream().anyMatch(value -> text(value).equalsIgnoreCase(action.code())); }
    private static Map<String, JsonNode> map(JsonNode node) { var result = new LinkedHashMap<String, JsonNode>(); node.properties().forEach(item -> result.put(item.getKey(), item.getValue())); return result; }
    private static ObjectNode attributes(Map<String, JsonNode> values) { var result = object(); values.forEach(result::set); return result; }
    private static AttachmentMetadata attachment(JsonNode file, String documentId) { String id = first(file, "attachmentId", "id"), reference = text(file, "storageReference"); if (id.isEmpty() || !documentId.equals(text(file, "documentId")) || reference.isEmpty()) throw new ApiException(400, "Неверные метаданные вложения"); return new AttachmentMetadata(id, id, documentId, text(file, "fileName"), text(file, "contentType"), number(file, "size", 0), 1, true, Instant.now(), new StorageReference(reference)); }
    private ObjectNode task(WorkflowTask value, AuthContext auth) { var result = object("id", value.id(), "documentId", value.documentId(), "documentType", value.documentType(), "status", value.status(), "type", value.title(), "title", value.title(), "description", value.description(), "assignee", value.assignee(), "attributes", attributes(value.attributes())); result.set("availableActions", actions(value)); result.set("completions", object("options", value.actions().stream().map(action -> object("label", action.label(), "result", attributes(action.parameters()))).toList())); return result; }
    private JsonNode executor(WorkflowTask value, AuthContext auth) { return object("login", value.assignee().isEmpty() ? null : value.assignee(), "name", value.assigneeName().isEmpty() ? null : value.assigneeName(), "role", value.assigneeRole().isEmpty() ? null : value.assigneeRole(), "roleLabel", roleLabel(value.assigneeRole(), auth), "taskStatus", value.status(), "taskTitle", value.title()); }
    private JsonNode action(WorkflowAction value) { var result = object("code", value.code(), "label", value.label(), "tone", value.tone(), "result", attributes(value.parameters())); if (!value.status().isEmpty()) result.put("status", value.status()); return result; }
    private static String taskText(WorkflowTask task) { return (task.title() + " " + task.description() + " " + task.attributes().values()).toLowerCase(Locale.ROOT); }
    private static JsonNode process(ProcessInstance value) { return object("id", value.id(), "documentId", value.documentId(), "state", value.state()); }
}
