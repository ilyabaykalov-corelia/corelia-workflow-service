# corelia-workflow-service

Внутренний сервис процессов и задач. Использует `corelia-provider-flowable`
для deploy/start/read BPMN definitions, поиска/claim/complete задач и
административного lifecycle definition. Не владеет правилами изменения
документа и вызывает document/data contracts только как участник orchestration.

База `corelia_workflow` принадлежит сервису; Liquibase master —
`classpath:db/changelog/workflow-master.yaml`. Flowable schema creation
управляется `CORELIA_FLOWABLE_DATABASE_SCHEMA_UPDATE` и должна выполняться
одним экземпляром до масштабирования.

```bash
mvn -pl corelia-workflow-service -am test
./scripts/up.sh
```

Требуются mTLS, customer configuration с Flowable bindings/BPMN и native
permissions provider. Внешний API проксирует gateway; детали —
[../docs/api.md](../docs/api.md).
