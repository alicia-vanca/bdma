package com.app.common.services;

import java.nio.file.Path;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.exceptions.AppException;
import com.app.common.models.PatchApplyLog;
import com.app.common.repositories.PatchApplyLogRepository;
import com.app.common.services.PatchCryptoService.DecryptResult;

/**
 * Applies encrypted SQL patches and records applied patch IDs.
 *
 * <p>Duplicate patch execution is protected by a pre-check and a DB unique constraint.
 */
@Service
public class PatchApplyLogService {

    private final PatchCryptoService         cryptoService;
    private final PatchApplyLogRepository applyRecordRepository;
    private final JdbcTemplate               jdbcTemplate;

    public PatchApplyLogService(PatchCryptoService cryptoService,
                             PatchApplyLogRepository applyRecordRepository,
                             JdbcTemplate jdbcTemplate) {
        this.cryptoService        = cryptoService;
        this.applyRecordRepository = applyRecordRepository;
        this.jdbcTemplate         = jdbcTemplate;
    }

    /**
     * Applies one encrypted patch file atomically.
     *
     * @param encFile encrypted {@code .sql.enc} file
     */
    @Transactional
    public void apply(Path encFile) {
        // 1. Decrypt + validate SQL (layer-1 duplicate guard runs inside)
        DecryptResult result = cryptoService.decryptAndValidate(encFile);

        // 2. Execute each validated INSERT / UPDATE statement
        List<String> statements = result.statements();
        for (String sql : statements) {
            jdbcTemplate.execute(sql);
        }

        // 3. Record successful apply — layer-2 duplicate guard via DB UNIQUE constraint
        try {
            applyRecordRepository.save(
                    new PatchApplyLog(result.patchId(), encFile.getFileName().toString()));
        } catch (DataIntegrityViolationException e) {
            throw new AppException(
                    "Patch [" + result.patchId() + "] was applied concurrently by another process. "
                    + "This execution's SQL has already run — review the database state manually.", e);
        }
    }
}
