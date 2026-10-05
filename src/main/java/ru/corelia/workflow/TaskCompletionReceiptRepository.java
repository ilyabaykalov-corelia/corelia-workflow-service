package ru.corelia.workflow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

import static ru.corelia.support.Json.*;

/** Хранит результат завершения задачи для безопасного повтора одной команды. */
@Repository
public class TaskCompletionReceiptRepository {
    private final JdbcTemplate jdbc;

    public TaskCompletionReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Receipt> find(String key) {
        return jdbc.query("select request_body, response from task_completion_receipt where idempotency_key = ?",
                (row, ignored) -> new Receipt(row.getString(1), parse(row.getString(2))), key).stream().findFirst();
    }

    public void save(String key, String body, JsonNode response) {
        jdbc.update("insert into task_completion_receipt (idempotency_key, request_body, response, completed_at) values (?, ?, cast(? as jsonb), ?)",
                key, body, write(response), Timestamp.from(Instant.now()));
    }

    public record Receipt(String body, JsonNode response) {}
}
