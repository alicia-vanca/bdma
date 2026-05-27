package com.app.common.config;

import com.app.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

@Configuration
public class SQLiteConfig {

    private static final Logger log = LoggerFactory.getLogger(SQLiteConfig.class);

    static {
        try {
            Class.forName("org.sqlite.JDBC");
            log.debug("SQLite driver loaded");
        } catch (ClassNotFoundException e) {
            throw new AppException("Cannot load SQLite JDBC driver: " + e.getMessage());
        }
    }

    @Bean
    public DataSource dataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + AppPaths.dataFile().getAbsolutePath());
        return ds;
    }
}