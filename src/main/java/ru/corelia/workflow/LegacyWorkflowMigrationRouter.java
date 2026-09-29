package ru.corelia.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import ru.corelia.auth.AuthContext;
import ru.corelia.provider.TaskProvider;
import ru.corelia.provider.WorkflowEngineResolver;
import ru.corelia.provider.WorkflowProvider;
import ru.corelia.provider.model.ProcessInstance;
import ru.corelia.provider.model.TaskSearchRequest;
import ru.corelia.provider.model.WorkflowContext;
import ru.corelia.provider.model.WorkflowTask;
import tools.jackson.databind.JsonNode;

/** Временно направляет Flowable и незавершённые Platform V процессы в их engine. */
@Component
@Primary
@ConditionalOnProperty(name = "corelia.workflow.legacy-platform-v.enabled", havingValue = "true")
public final class LegacyWorkflowMigrationRouter implements WorkflowProvider, TaskProvider {
    private final WorkflowProvider flowableWorkflows;
    private final WorkflowProvider platformWorkflows;
    private final TaskProvider flowableTasks;
    private final TaskProvider platformTasks;
    private final WorkflowEngineResolver flowable;

    public LegacyWorkflowMigrationRouter(
            @Qualifier("flowableWorkflowProvider") WorkflowProvider flowableWorkflows,
            @Qualifier("platformVWorkflowProvider") WorkflowProvider platformWorkflows,
            @Qualifier("flowableTaskProvider") TaskProvider flowableTasks,
            @Qualifier("platformVTaskProvider") TaskProvider platformTasks,
            WorkflowEngineResolver flowable) {
        this.flowableWorkflows = flowableWorkflows;
        this.platformWorkflows = platformWorkflows;
        this.flowableTasks = flowableTasks;
        this.platformTasks = platformTasks;
        this.flowable = flowable;
    }

    @Override public ProcessInstance start(WorkflowContext context, AuthContext auth) { return flowableWorkflows.start(context, auth); }
    @Override public ProcessInstance process(String id, AuthContext auth) { return flowable.ownsProcess(id) ? flowableWorkflows.process(id, auth) : platformWorkflows.process(id, auth); }
    @Override public List<WorkflowTask> search(TaskSearchRequest request, AuthContext auth) { return unique(flowableTasks.search(request, auth), platformTasks.search(request, auth)); }
    @Override public List<WorkflowTask> findByDocument(String id, AuthContext auth) { return unique(flowableTasks.findByDocument(id, auth), platformTasks.findByDocument(id, auth)); }
    @Override public WorkflowTask task(String id, AuthContext auth) { return flowable.ownsTask(id) ? flowableTasks.task(id, auth) : platformTasks.task(id, auth); }
    @Override public String roleLabel(String role, AuthContext auth) { return flowableTasks.roleLabel(role, auth); }
    @Override public void start(String id, AuthContext auth) { if (flowable.ownsTask(id)) flowableTasks.start(id, auth); else platformTasks.start(id, auth); }
    @Override public void complete(String id, Map<String, JsonNode> parameters, AuthContext auth) { if (flowable.ownsTask(id)) flowableTasks.complete(id, parameters, auth); else platformTasks.complete(id, parameters, auth); }

    private static List<WorkflowTask> unique(List<WorkflowTask> first, List<WorkflowTask> second) {
        var result = new LinkedHashMap<String, WorkflowTask>();
        first.forEach(task -> result.putIfAbsent(task.id(), task)); second.forEach(task -> result.putIfAbsent(task.id(), task));
        return List.copyOf(result.values());
    }
}
