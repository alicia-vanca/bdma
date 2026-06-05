package db.migration;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class V11__Create_Sync_Triggers extends BaseJavaMigration {
    static {
        System.out.println("V11 CLASS LOADED");
    }
    @Override
    public void migrate(Context context) throws Exception {
        System.out.println("migrate đã chạy");
        Connection conn = context.getConnection();
        String sql = """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                  AND name NOT LIKE 'sqlite_%'
                  AND name <> 'flyway_schema_history'
                  AND name <> 'sync_log'
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {

                String tableName = rs.getString("name");
                if(tableName.equals("user_config")){
                    System.out.println("skip table");
                }
                List<String> pkColumns =
                        getPrimaryKeys(conn, tableName);
                System.out.println("Found table: " + tableName);
                if (pkColumns.isEmpty()) {
                    continue;
                }
                String recordExpr = buildKeyExpression(pkColumns,"NEW");

                String oldExpr = buildKeyExpression(pkColumns, "OLD");
                try (Statement triggerStmt = conn.createStatement()) {

                    // INSERT
                    try {
                        String insertTriggerSql = createInsertTrigger(
                                tableName,
                                recordExpr);

                        triggerStmt.executeUpdate(insertTriggerSql);

                        //logger.info("Created INSERT trigger for table {}", tableName);
                    } catch (SQLException e) {
                        //logger.error("Failed to create INSERT trigger for table {}", tableName, e);
                        System.out.println("insert");
                        throw e;
                    }

                    // UPDATE
                    try {
                        String updateTriggerSql = createUpdateTrigger(
                                tableName,
                                oldExpr);

                        triggerStmt.executeUpdate(updateTriggerSql);

                        //logger.info("Created UPDATE trigger for table {}", tableName);
                    } catch (SQLException e) {
                        //logger.error("Failed to create UPDATE trigger for table {}", tableName, e);
                        System.out.println("update");
                        throw e;
                    }

                    // DELETE
                    try {
                        String deleteTriggerSql = createDeleteTrigger(
                                tableName,
                                oldExpr);

                        triggerStmt.executeUpdate(deleteTriggerSql);

                        //logger.info("Created DELETE trigger for table {}", tableName);
                    } catch (SQLException e) {
                        System.out.println("delete");
                        //logger.error("Failed to create DELETE trigger for table {}", tableName, e);
                        throw e;
                    }
                } catch (Exception e) {
                    System.out.println("all");
                    throw  e;
                }

                // TODO: tạo trigger cho tableName
            }
        }
    }
    private List<String> getPrimaryKeys(
            Connection connection,
            String tableName) throws SQLException {

        List<String> pkColumns = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "PRAGMA table_info('" + tableName + "')")) {

            while (rs.next()) {
                int pk = rs.getInt("pk");

                if (pk > 0) {
                    pkColumns.add(rs.getString("name"));
                }
            }
        }

        return pkColumns;
    }
    private String buildKeyExpression(
            List<String> primaryKeys,
            String prefix) {

        return primaryKeys.stream()
                .map(pk -> prefix + "." + pk)
                .collect(Collectors.joining(" || ':' || "));
    }
    private String createInsertTrigger(
            String tableName,
            String recordExpr) {

        return String.format("""
        CREATE TRIGGER IF NOT EXISTS trg_%s_insert
        AFTER INSERT ON %s
        BEGIN
            INSERT INTO sync_log (
                table_name,
                operation,
                record_id
            )
            VALUES (
                '%s',
                'INSERT',
                %s
            );
        END;
        """,
                tableName,
                tableName,
                tableName,
                recordExpr);
    }
    private String createUpdateTrigger(
            String tableName,
            String recordExpr) {

        return """
        CREATE TRIGGER IF NOT EXISTS trg_%s_update
        AFTER UPDATE ON %s
        BEGIN
            INSERT INTO sync_log(
                table_name,
                operation,
                record_id)
            VALUES(
                '%s',
                'UPDATE',
                %s);
        END;
        """
                .formatted(
                        tableName,
                        tableName,
                        tableName,
                        recordExpr);
    }
    private String createDeleteTrigger(
            String tableName,
            String oldExpr) {

        return """
        CREATE TRIGGER IF NOT EXISTS trg_%s_delete
        AFTER DELETE ON %s
        BEGIN
            INSERT INTO sync_log(
                table_name,
                operation,
                record_id)
            VALUES(
                '%s',
                'DELETE',
                %s);
        END;
        """
                .formatted(
                        tableName,
                        tableName,
                        tableName,
                        oldExpr);
    }
}
