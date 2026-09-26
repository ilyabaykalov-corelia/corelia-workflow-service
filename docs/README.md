# Документация workflow-service

Сервис запускает процессы, получает tasks и исполняет workflow actions через `WorkflowProvider`. Он не владеет бизнес-правилами изменения документа. Внутренний API: `/internal/v1/processes` и `/internal/v1/tasks`. См. [architecture](../../docs/architecture.md) и [API](../../docs/api.md).
