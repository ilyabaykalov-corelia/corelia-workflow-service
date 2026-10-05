# Документация workflow-service

Внутренние endpoint `/internal/v1/processes` и `/internal/v1/tasks` принимают
только доверенные сервисы. Определения имеют lifecycle `DRAFT`, `VALIDATED`,
`PUBLISHED`, `RETIRED`; публикация не переписывает уже запущенные Flowable
instances. BPMN service task передаётся владельцу domain command через
`WorkflowServiceTaskExecutor`.

Действие задачи допустимо лишь когда provider возвращает его как transition.
Gateway и frontend не выбирают переход самостоятельно. Архитектурная граница:
[workflow ADR](../../docs/adr/platform-v-007-bpmn-lifecycle.md).

## Runtime-визуализация BPMN

Административные endpoints `GET /internal/v1/admin/workflows/{key}/runtime` и
`GET /internal/v1/admin/workflows/{key}/active-documents?activityId=...`
возвращают только provider-neutral runtime-данные. Счётчик связан с BPMN по
стабильному `activityId`, а не по отображаемому имени элемента. Workflow service
одним поиском получает доступные вызывающему документы, поэтому не раскрывает
недоступные экземпляры и не выполняет запрос к документу для каждой строки.
