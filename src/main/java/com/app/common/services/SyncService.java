package com.app.common.services;

import com.app.common.models.SyncLog;
import com.app.common.repositories.SyncLogRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SyncService {

    private final SyncLogRepository repository;

    public SyncService(SyncLogRepository repository) {
        this.repository = repository;
    }

    // =========================
    // MAIN SYNC LOOP
    // =========================
    @Scheduled(fixedDelay = 5000)
    public void sync() {
        List<SyncLog> logs = repository.findPendingLogs();
        for (SyncLog log : logs) {

            String table = log.getTableName();
            String recordId = log.getRecordId();
            String operation = log.getOperation();

            try {

                // DELETE
                if ("DELETE".equalsIgnoreCase(operation)) {

                    List<String> pkColumns = repository.getPrimaryKeyColumns(table);
                    repository.deleteRecordInB(
                            table,
                            pkColumns,
                            recordId
                    );
                    repository.markSynced(log.getId());
                    continue;
                }

                // INSERT / UPDATE
                List<Map<String, Object>> records = repository.getRecordFromA(table, recordId);
                for(Map<String, Object> record : records){
                    if (record == null) {
                        continue;
                    }
                    repository.upsertToB(table, record);

                    repository.markSynced(log.getId());
                }
            } catch (Exception e) {
                e.getMessage();
            }
        }
    }
}