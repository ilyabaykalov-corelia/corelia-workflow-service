package ru.corelia.workflow;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.TaskProvider;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.WorkflowServiceTaskExecutor;
import ru.corelia.provider.model.*;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Сценарии Corelia для процессов и задач без знания provider transport. */
@Service
public class WorkflowService implements WorkflowServiceTaskExecutor {
    private final DocumentTypeCatalog types; private final DocumentStore documents; private final DocumentVersionStore versions; private final WorkflowProvider workflows; private final TaskProvider tasks; private final ServiceClient services; private final WorkflowDraftRepository drafts; private final boolean designerEnabled; private final boolean designerEditEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowService(DocumentTypeCatalog types, DocumentStore documents, DocumentVersionStore versions, WorkflowProvider workflows, TaskProvider tasks, ServiceClient services, WorkflowDraftRepository drafts, @Value("${corelia.designer.enabled:true}") boolean designerEnabled, @Value("${corelia.designer.edit-enabled:true}") boolean designerEditEnabled) { this.types = types; this.documents = documents; this.versions = versions; this.workflows = workflows; this.tasks = tasks; this.services = services; this.drafts = drafts; this.designerEnabled = designerEnabled; this.designerEditEnabled = designerEditEnabled; }
    public WorkflowService(DocumentTypeCatalog types, DocumentStore documents, DocumentVersionStore versions, WorkflowProvider workflows, TaskProvider tasks) { this(types, documents, versions, workflows, tasks, null, null, true, true); }
    public JsonNode search(JsonNode body, AuthContext auth) {
        String queue = text(body, "queue").toUpperCase(Locale.ROOT), requested = text(body, "status").toUpperCase(Locale.ROOT), query = text(body, "query").toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("NEW", "ASSIGNED", "STARTED", "COMPLETED", "ABORTED"); Set<String> statuses = allowed.contains(requested) ? Set.of(requested) : Set.of("NEW", "ASSIGNED", "STARTED");
        List<WorkflowTask> found = tasks.search(new TaskSearchRequest(statuses), auth).stream().filter(task -> queue.equals("MY") ? auth.login().equalsIgnoreCase(task.assignee()) : !queue.equals("AVAILABLE") || task.assignee().isEmpty()).filter(task -> query.isEmpty() || taskText(task).contains(query)).toList();
        return object("items", found.stream().map(task -> task(task, auth)).toList(), "total", found.size());
    }
    public JsonNode summary(AuthContext auth) { return object("my", number(search(object("queue", "MY"), auth), "total", 0), "available", number(search(object("queue", "AVAILABLE"), auth), "total", 0)); }
    /** Возвращает provider-neutral перечень опубликованных процессов для административного UI. */
    public JsonNode definitions(AuthContext auth) {
        designer(); var definitions = new TreeMap<String, WorkflowDefinition>();
        workflows.definitions(auth).forEach(value -> definitions.put(value.key(), value));
        if (drafts != null) for (var draft : drafts.all()) definitions.compute(draft.key(), (key, published) -> published == null
                ? new WorkflowDefinition(draft.name(), draft.key(), 0, true, "DRAFT", null, null, 0)
                : new WorkflowDefinition(published.name(), published.key(), published.publishedVersion(), true, published.status(),
                        published.lastPublishedAt(), published.publishedBy(), published.activeInstances()));
        return object("editEnabled", designerEditEnabled, "items", definitions.values().stream().map(value -> object(
                "name", value.name(), "key", value.key(), "publishedVersion", value.publishedVersion(),
                "draft", value.draft(), "status", value.status(), "lastPublishedAt", value.lastPublishedAt() == null ? null : value.lastPublishedAt().toString(),
                "publishedBy", value.publishedBy(), "activeInstances", value.activeInstances())).toList());
    }
    public JsonNode createDraft(JsonNode body, AuthContext auth) {
        editable();
        String key = workflowKey(text(body, "key")), name = workflowName(text(body, "name"));
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        if (drafts.find(key).isPresent()) throw new ApiException(409, "Черновик процесса с таким ключом уже существует");
        var draft = new WorkflowDraftRepository.Draft(key, name, first(body, "bpmnXml", "xml").isBlank() ? emptyBpmn(key, name) : first(body, "bpmnXml", "xml"), Instant.now(), auth.login());
        drafts.create(draft); drafts.audit(key, "created", auth.login());
        return draft(draft);
    }
    public JsonNode draft(String key) {
        designer();
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        return draft(drafts.find(workflowKey(key)).orElseThrow(() -> new ApiException(404, "Черновик процесса не найден")));
    }
    public JsonNode saveDraft(String key, JsonNode body, AuthContext auth) {
        editable();
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        var current = drafts.find(workflowKey(key)).orElseThrow(() -> new ApiException(404, "Черновик процесса не найден"));
        String name = body.has("name") ? workflowName(text(body, "name")) : current.name();
        String xml = first(body, "bpmnXml", "xml"); if (xml.isBlank()) throw new ApiException(400, "Не задан BPMN XML черновика");
        var next = new WorkflowDraftRepository.Draft(current.key(), name, xml, Instant.now(), auth.login());
        drafts.update(next); drafts.audit(next.key(), "edited", auth.login());
        return draft(next);
    }
    /** Проверяет сохранённый черновик перед публикацией и сохраняет факт успешной проверки в журнале. */
    public JsonNode validateDraft(String key, AuthContext auth) {
        editable();
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        var current = drafts.find(workflowKey(key)).orElseThrow(() -> new ApiException(404, "Черновик процесса не найден"));
        var validation = workflows.validateDefinition(current.key(), current.bpmnXml(), auth);
        if (validation.valid()) drafts.audit(current.key(), "validated", auth.login());
        return object("valid", validation.valid(), "errors", validation.errors().stream()
                .map(error -> object("code", error.code(), "message", error.message())).toList());
    }
    /** Публикует сохранённый и проверенный BPMN как неизменяемую версию workflow provider. */
    public JsonNode publishDraft(String key, AuthContext auth) {
        editable();
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        var current = drafts.find(workflowKey(key)).orElseThrow(() -> new ApiException(404, "Черновик процесса не найден"));
        var validation = workflows.validateDefinition(current.key(), current.bpmnXml(), auth);
        if (!validation.valid()) return object("published", false, "valid", false, "errors", validation.errors().stream()
                .map(error -> object("code", error.code(), "message", error.message())).toList());
        var definition = workflows.publishDefinition(current.key(), current.name(), current.bpmnXml(), auth);
        drafts.audit(current.key(), "published", auth.login());
        return object("published", true, "valid", true, "key", definition.key(), "version", definition.publishedVersion(),
                "publishedAt", definition.lastPublishedAt().toString());
    }
    /** Импортирует XML в существующий черновик без публикации процесса. */
    public JsonNode importDraft(String key, JsonNode body, AuthContext auth) {
        editable();
        JsonNode saved = saveDraft(key, body, auth);
        if (drafts != null) drafts.audit(workflowKey(key), "imported", auth.login());
        return saved;
    }
    /** Экспортирует сохранённый XML черновика для переноса между средами. */
    public JsonNode exportDraft(String key) {
        designer();
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        var current = drafts.find(workflowKey(key)).orElseThrow(() -> new ApiException(404, "Черновик процесса не найден"));
        return object("key", current.key(), "name", current.name(), "bpmnXml", current.bpmnXml());
    }
    /** Выводит опубликованный процесс из эксплуатации, не удаляя его историю. */
    public JsonNode retireDraft(String key, AuthContext auth) {
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        String workflowKey = workflowKey(key);
        workflows.retireDefinition(workflowKey, auth);
        drafts.audit(workflowKey, "retired", auth.login());
        return object("retired", true, "key", workflowKey);
    }
    /** Возвращает неизменяемый журнал административных операций процесса. */
    public JsonNode audit(String key) {
        designer();
        if (drafts == null) throw new IllegalStateException("Не настроено хранилище workflow drafts");
        String workflowKey = workflowKey(key);
        if (drafts.find(workflowKey).isEmpty()) throw new ApiException(404, "Черновик процесса не найден");
        return object("items", drafts.history(workflowKey).stream().map(event -> object(
                "event", event.eventType(), "at", event.occurredAt().toString(), "by", event.occurredBy())).toList());
    }
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
    /** Выполняет идемпотентную document command из асинхронной BPMN service task. */
    @Override
    public void execute(WorkflowServiceTask task) {
        if (!"document-command".equals(task.taskType()))
            throw new IllegalArgumentException("Неподдерживаемый тип BPMN service task: " + task.taskType());
        if (task.documentId().isBlank() || task.documentType().isBlank() || task.command().isBlank())
            throw new IllegalArgumentException("BPMN service task не содержит контекст документа или команду");
        types.requireType(task.documentType());
        if (!types.definition(task.documentType()).workflow().path("commands").path(task.command()).isObject())
            throw new IllegalArgumentException("Workflow-команда не настроена для вида документа: " + task.command());
        if (services == null) throw new IllegalStateException("Не настроен внутренний клиент document-service");
        JsonNode state = services.call("document", "/internal/v1/documents/" + task.documentType() + "/" + task.documentId(), "GET", null, null);
        String requestId = UUID.nameUUIDFromBytes((task.processInstanceId() + ":" + task.executionId() + ":" + task.taskId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        services.call("document", "/internal/v1/documents/" + task.documentType() + "/" + task.documentId() + "/workflow-commands/" + task.command(), "POST", object("requestId", requestId, "expectedVersion", number(state, "currentVersion", -1), "changeToken", text(state, "changeToken")), null);
    }
    public JsonNode requireTask(String id, AuthContext auth) { WorkflowTask task = tasks.task(id, auth); if (task == null) throw new ApiException(404, "Активная задача не найдена"); return task(task, auth); }
    WorkflowTask taskModel(String id, AuthContext auth) { WorkflowTask task = tasks.task(id, auth); if (task == null) throw new ApiException(404, "Активная задача не найдена"); return task; }
    public JsonNode actions(WorkflowTask task) { return array(task.actions().stream().map(this::action).toList()); }
    public JsonNode start(String id, AuthContext auth) { tasks.start(id, auth); return object("operationResults", List.of(object("userTaskId", id, "responseType", "SUCCESS"))); }
    public JsonNode complete(String id, JsonNode payload, AuthContext auth) {
        WorkflowTask task = taskModel(id, auth); String code = first(payload, "actionCode", "status", "decision"); JsonNode supplied = payload.path("parameters");
        WorkflowAction selected = task.actions().stream().filter(action -> !code.isEmpty() && code.equalsIgnoreCase(action.code()) || supplied.isObject() && supplied.equals(attributes(action.parameters()))).findFirst().orElseThrow(() -> new ApiException(400, "Действие недоступно для текущей задачи"));
        String type = type(task, auth); if (!"STARTED".equals(task.status())) tasks.start(id, auth); var parameters = new LinkedHashMap<>(selected.parameters()); JsonNode completion = types.definition(type).workflow().path("completion"); boolean assigned = takeInWork(selected, completion); if (assigned) parameters.put(text(completion, "assigneeField"), MAPPER.getNodeFactory().textNode(auth.login())); commitDocumentCommand(task, selected, type, auth); tasks.complete(id, parameters, auth); if (assigned && completion.path("autoStart").asBoolean()) tasks.findByDocument(task.documentId(), auth).stream().filter(next -> !id.equals(next.id())).filter(next -> Set.of("NEW", "ASSIGNED").contains(next.status())).findFirst().ifPresent(next -> tasks.start(next.id(), auth)); return object("operationResults", List.of(object("userTaskId", id, "responseType", "SUCCESS")));
    }
    private void commitDocumentCommand(WorkflowTask task, WorkflowAction action, String type, AuthContext auth) {
        JsonNode commands = types.definition(type).workflow().path("commands"); String command = commands.properties().stream().filter(entry -> action.status().equals(text(entry.getValue(), "to"))).map(Map.Entry::getKey).findFirst().orElse("");
        if (command.isEmpty()) return;
        if (services == null) throw new IllegalStateException("Не настроен внутренний клиент document-service");
        JsonNode state = services.call("document", "/internal/v1/documents/" + type + "/" + task.documentId(), "GET", null, auth);
        String requestId = UUID.nameUUIDFromBytes((task.id() + ":" + command).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        services.call("document", "/internal/v1/documents/" + type + "/" + task.documentId() + "/workflow-commands/" + command, "POST", object("requestId", requestId, "expectedVersion", number(state, "currentVersion", -1), "changeToken", text(state, "changeToken")), auth);
    }
    public String roleLabel(String role, AuthContext auth) { return tasks.roleLabel(role, auth); }
    private String type(WorkflowTask task, AuthContext auth) { String type = task.documentType(); if (type.isEmpty()) type = types.types().stream().filter(value -> { try { return documents.get(value, task.documentId(), auth) != null; } catch (ApiException ignored) { return false; }}).findFirst().orElseThrow(() -> new ApiException(404, "Документ задачи недоступен")); types.requireType(type); return type; }
    private static int priority(String status) { return switch (status) { case "STARTED" -> 0; case "ASSIGNED" -> 1; case "NEW" -> 2; default -> 3; }; }
    private void designer() { if (!designerEnabled) throw new ApiException(404, "BPMN designer отключён конфигурацией"); }
    private void editable() { designer(); if (!designerEditEnabled) throw new ApiException(403, "Редактирование BPMN отключено конфигурацией"); }
    private static String workflowKey(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) throw new ApiException(400, "Некорректный ключ процесса");
        return value;
    }
    private static String workflowName(String value) {
        if (value.isBlank() || value.length() > 255) throw new ApiException(400, "Некорректное название процесса");
        return value;
    }
    private static JsonNode draft(WorkflowDraftRepository.Draft value) { return object("key", value.key(), "name", value.name(), "bpmnXml", value.bpmnXml(), "updatedAt", value.updatedAt().toString(), "updatedBy", value.updatedBy()); }
    private static String emptyBpmn(String key, String name) { return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:corelia="urn:corelia:bpmn" targetNamespace="urn:corelia:bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true"><bpmn:startEvent id="start" /></bpmn:process>
              <bpmndi:BPMNDiagram id="BPMNDiagram_1"><bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="%s"><bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="152" y="102" width="36" height="36" /></bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """.formatted(key, xml(name), key); }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }
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
