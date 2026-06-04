package com.app.common.repositories;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.app.common.models.SyncLog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class SyncLogRepository {

    private final JdbcTemplate jdbcTemplateA;
    private final JdbcTemplate jdbcTemplateB;

    public SyncLogRepository(
            @Qualifier("jdbcTemplateA") JdbcTemplate jdbcTemplateA,
            @Qualifier("jdbcTemplateB") JdbcTemplate jdbcTemplateB) {

        this.jdbcTemplateA = jdbcTemplateA;
        this.jdbcTemplateB = jdbcTemplateB;
    }

    // =========================
    // 1. GET pending logs
    // =========================
    public List<SyncLog> findPendingLogs() {

        String sql = """
            SELECT id, table_name, operation, record_id, synced, created_at
            FROM sync_log
            WHERE synced = 0
            ORDER BY id
        """;

        return jdbcTemplateA.query(sql, (rs, rowNum) -> {

            SyncLog log = new SyncLog();

            log.setId(rs.getLong("id"));
            log.setTableName(rs.getString("table_name"));
            log.setOperation(rs.getString("operation"));
            log.setRecordId(rs.getString("record_id"));
            log.setSynced(rs.getInt("synced"));
            log.setCreatedAt(rs.getString("created_at"));

            return log;
        });
    }

    // =========================
    // 2. MARK SYNCED
    // =========================
    public void markSynced(Long syncLogId) {

        jdbcTemplateA.update("""
            UPDATE sync_log
            SET synced = 1
            WHERE id = ?
        """, syncLogId);
    }

    // =========================
    // 3. GET RECORD FROM DB A (dynamic PK)
    // =========================
    public List<Map<String, Object>> getRecordFromA(String tableName, String recordId) {
        String[] pkValues = recordId.split(":");
        List<String> pkColumns = getPrimaryKeyColumns(tableName);
        String whereClause = getClause(tableName,pkColumns,recordId,pkValues);
        String sql = "SELECT * FROM " + tableName + " WHERE " + whereClause;


        try {

            return jdbcTemplateA.queryForList(sql, (Object[]) pkValues);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // =========================
    // 4. DELETE IN DB B
    // =========================
    public void deleteRecordInB(
            String tableName,
            List<String> pkColumns,
            String recordId) {
        String[] pkValues = recordId.split(":");
        String whereClause = getClause(tableName,pkColumns,recordId,pkValues);
        String sql = "DELETE FROM " + tableName + " WHERE " + whereClause;
        jdbcTemplateB.update(sql, recordId);
    }

    // =========================
    // 5. UPSERT TO DB B (FIXED MAP ORDER)
    // =========================
    public void upsertToB(
            String tableName,
            Map<String, Object> data) {

        String columns = String.join(", ", data.keySet());

        String placeholders = data.keySet()
                .stream()
                .map(k -> "?")
                .collect(Collectors.joining(", "));

        String sql = String.format(
                "INSERT OR REPLACE INTO %s (%s) VALUES (%s)",
                tableName,
                columns,
                placeholders
        );

        Object[] params = data.keySet()
                .stream()
                .map(data::get)
                .toArray();

        jdbcTemplateB.update(sql, params);
    }
    /*public String getPrimaryKeyColumn(String tableName) {
        return jdbcTemplateA.queryForObject(
                "SELECT l.name " +
                        "FROM pragma_table_info(?) l " +
                        "WHERE l.pk = 1",
                String.class,
                tableName
        );
    }*/
    public List<String> getPrimaryKeyColumns(String tableName) {
        return jdbcTemplateA.query(
                "SELECT name " +
                        "FROM pragma_table_info(?) " +
                        "WHERE pk > 0 " +
                        "ORDER BY pk",
                (rs, rowNum) -> rs.getString("name"),
                tableName
        );
    }
    public String getClause(String tableName,List<String> pkColumns,String recordId,String[] pkValues){
        if (pkColumns.size() != pkValues.length) {
            throw new IllegalArgumentException(
                    String.format(
                            "Primary key count (%d) does not match recordId value count (%d). table=%s, recordId=%s",
                            pkColumns.size(),
                            pkValues.length,
                            tableName,
                            recordId
                    )
            );
        }

        String whereClause = IntStream.range(0, pkColumns.size())
                .mapToObj(i -> pkColumns.get(i) + " = ?")
                .collect(Collectors.joining(" AND "));
        return whereClause;
    }

}