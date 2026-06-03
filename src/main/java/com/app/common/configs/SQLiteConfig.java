package com.app.common.configs;

import com.app.common.definitions.AppDataPaths;
import com.app.common.exceptions.AppException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.sqlite.SQLiteDataSource;
import org.sqlite.mc.SQLiteMCWxAES256Config;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Configuration
public class SQLiteConfig {
    private static final Logger log = LoggerFactory.getLogger(SQLiteConfig.class);

    @Bean
    @Primary
    public DataSource dataSourceA() {
        setDatasourceA();
        if (!AppContext.isDbEncryptionEnabled()) {
            HikariDataSource ds = new HikariDataSource ();
            ds.setJdbcUrl("jdbc:sqlite:" + AppDataPaths.dataFile().getAbsolutePath());
            return ds;
        }

        byte[] key = AppContext.getDbKey();
        if (key == null) {
            throw new AppException(
                    "Database key not initialized - ensure AppRuntimeInitializer ran before Spring context");
        }
        var config = SQLiteMCWxAES256Config.getDefault()
                .withKey(AppRuntimeInitializer.toRawKey(key))
                .build();
        SQLiteDataSource sqliteDs = new SQLiteDataSource(config);
        sqliteDs.setUrl("jdbc:sqlite:" + AppDataPaths.dataFile().getAbsolutePath());
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqliteDs);
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(1);
        return new HikariDataSource(hikariConfig);
    }
    @Bean
    public DataSource dataSourceB() {
        setDatasourceB();
        if (!AppContext.isDbEncryptionEnabled()) {
            HikariDataSource ds = new HikariDataSource ();
            ds.setJdbcUrl("jdbc:sqlite:" + AppDataPaths.dataFileDataBackup().getAbsolutePath());
            return ds;
        }

        byte[] key = AppContext.getDbKey();
        if (key == null) {
            throw new AppException(
                    "Database key not initialized - ensure AppRuntimeInitializer ran before Spring context");
        }
        var config = SQLiteMCWxAES256Config.getDefault()
                .withKey(AppRuntimeInitializer.toRawKey(key))
                .build();
        SQLiteDataSource sqliteDs = new SQLiteDataSource(config);
        sqliteDs.setUrl("jdbc:sqlite:" + AppDataPaths.dataFileDataBackup().getAbsolutePath());
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqliteDs);
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(1);
        return new HikariDataSource(hikariConfig);
    }
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplateA(
            @Qualifier("dataSourceA") DataSource ds) {

        return new JdbcTemplate(ds);
    }
    @Bean
    public JdbcTemplate jdbcTemplateB(
            @Qualifier("dataSourceB") DataSource ds) {

        return new JdbcTemplate(ds);
    }
    public void setDatasourceA(){
        try{
            Path dbBackupFolder= Paths.get(AppDataPaths.syncDatabaseForderDir());
            if (!Files.exists(dbBackupFolder)) {
                Files.createDirectories(dbBackupFolder);
            }
            Path dbBackupFile = Paths.get(AppDataPaths.syncDatabaseFileDir());
            if (Files.exists(dbBackupFile)) {
                Path dbRootPath=Paths.get(AppDataPaths.dataFile().getAbsolutePath());
                if(!Files.exists(dbRootPath)){
                    Files.copy(
                            dbBackupFile,
                            AppDataPaths.dataFile().toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }else{
                    File dbRoot=AppDataPaths.dataFile();
                    File dbBackup=AppDataPaths.dataFileDataBackup();
                    if(dbRoot.length()<dbBackup.length()) {
                        Files.copy(
                                dbBackupFile,
                                AppDataPaths.dataFile().toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                }
            }
            log.info("Restore database file");
        } catch (Exception e) {
            log.error("Failed to create databaseBackup file",e);
        }
    }
    public void setDatasourceB(){
        try{
            Path dbBackupFolder= Paths.get(AppDataPaths.syncDatabaseForderDir());
            if (!Files.exists(dbBackupFolder)) {
                Files.createDirectories(dbBackupFolder);
            }
            Path dbBackupFile = Paths.get(AppDataPaths.syncDatabaseFileDir());

            if (Files.notExists(dbBackupFile)) {
                Files.copy(
                        AppDataPaths.dataFile().toPath(),
                        dbBackupFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
                log.info("Copy database root to backup folder");
            }
        }catch (Exception e){
            log.error("Failed to create databaseBackup file",e);
        }
    }
}
