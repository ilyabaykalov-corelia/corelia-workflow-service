# Документация workflow-service

Внутренние endpoint `/internal/v1/processes` и `/internal/v1/tasks` принимают
только доверенные сервисы. Определения имеют lifecycle `DRAFT`, `VALIDATED`,
`PUBLISHED`, `RETIRED`; публикация не переписывает уже запущенные Flowable
instances. BPMN service task передаётся владельцу domain command через
`WorkflowServiceTaskExecutor`.

Публикация использует Flowable process key как логическую identity и создаёт
новую immutable definition version. Bootstrap customer configuration сравнивает
её checksum с latest version: повторный startup с неизменным BPMN не создаёт
definition, а runtime-изменения заменяются новой latest version из
configuration package. Экспорт текущей опубликованной версии позволяет явно
перенести её обратно в customer config.

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
