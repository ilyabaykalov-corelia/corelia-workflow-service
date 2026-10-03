# Документация workflow-service

Внутренние endpoint `/internal/v1/processes` и `/internal/v1/tasks` принимают
только доверенные сервисы. Определения имеют lifecycle `DRAFT`, `VALIDATED`,
`PUBLISHED`, `RETIRED`; публикация не переписывает уже запущенные Flowable
instances. BPMN service task передаётся владельцу domain command через
`WorkflowServiceTaskExecutor`.

Действие задачи допустимо лишь когда provider возвращает его как transition.
Gateway и frontend не выбирают переход самостоятельно. Архитектурная граница:
[workflow ADR](../../docs/adr/platform-v-007-bpmn-lifecycle.md).
