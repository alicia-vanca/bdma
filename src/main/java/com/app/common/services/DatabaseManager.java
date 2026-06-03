package com.app.common.services;

import com.app.common.configs.AppContext;
import com.app.common.configs.AppRuntimeInitializer;
import com.app.common.definitions.AppDataPaths;
import com.app.common.exceptions.AppException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteDataSource;
import org.sqlite.mc.SQLiteMCWxAES256Config;

import javax.sql.DataSource;

@Getter
@Setter
@Service
public class DatabaseManager {

    private DataSource dataSourceA;
    private DataSource dataSourceB;

    public DatabaseManager(
            @Qualifier("dataSourceA") DataSource dataSourceA,
            @Qualifier("dataSourceB") DataSource dataSourceB) {

        this.dataSourceA = dataSourceA;
        this.dataSourceB = dataSourceB;
    }

    public void shutdown() {

        close(dataSourceA);
        close(dataSourceB);
    }
    public void closeA(){
        close(dataSourceA);
    }
    public void closeB(){
        close(dataSourceB);
    }

    private void close(DataSource ds) {

        if (ds instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}