package com.app.dev.patchmanager.services;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.exceptions.AppException;
import com.app.common.models.PatchApplyLog;
import com.app.common.repositories.PatchApplyLogRepository;
import com.app.dev.patchmanager.services.PatchCryptoService.DecryptResult;

/**
 * Applies encrypted SQL patches and records applied patch IDs.
 *
 * <p>
 * Duplicate patch execution is protected by a synchronized pre-check before any
 * SQL statements run.
 */
@Service
public class PatchApplyLogService {

    private final PatchCryptoService cryptoService;
    private final PatchApplyLogRepository applyRecordRepository;
    private final JdbcTemplate jdbcTemplate;

    public PatchApplyLogService(PatchCryptoService cryptoService,
            PatchApplyLogRepository applyRecordRepository,
            JdbcTemplate jdbcTemplate) {
        this.cryptoService = cryptoService;
        this.applyRecordRepository = applyRecordRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Applies one encrypted patch file atomically.
     *
     * @param encFile encrypted {@code .sql.enc} file
     */
    @Transactional
    public synchronized void apply(Path encFile) {
        // Decrypt and validate inside the same monitor as execution so duplicate
        // patch IDs are rejected before any SQL can run.
        DecryptResult result = cryptoService.decryptAndValidate(encFile);

        List<String> statements = result.statements();
        for (String sql : statements) {
            try {
                jdbcTemplate.execute(Objects.requireNonNull(sql, "Patch SQL statement must not be null"));
            } catch (DataAccessException e) {
                // SQL errors mean the patch does not match current app data; the transaction
                // rolls back all prior statements.
                throw new AppException("Patch SQL is incompatible with current app data.", e);
            }
        }

        applyRecordRepository.save(new PatchApplyLog(result.patchId(), encFile.getFileName().toString()));
    }

    /**
     * Lists applied patches newest-first for display.
     *
     * @return applied patch logs
     */
    public List<PatchApplyLog> listAppliedPatches() {
        return applyRecordRepository.findAllNewestFirst();
    }
}