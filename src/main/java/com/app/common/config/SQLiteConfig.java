package com.app.common.config;

import com.app.common.exception.AppException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;
import org.sqlite.mc.SQLiteMCWxAES256Config;

import javax.sql.DataSource;

@Configuration
public class SQLiteConfig {

    // SQLiteMC expects raw keys as lower-case hex prefixed with raw:
    private static String toRawKey(byte[] key) {
        StringBuilder hex = new StringBuilder(key.length * 2);
        for (byte b : key) {
            hex.append(String.format("%02x", b));
        }
        return "raw:" + hex;
    }

    @Bean
    public DataSource dataSource() {
        byte[] key = AppContext.getDbKey();
        if (key == null) {
            throw new AppException(
                    "Database key not initialized - ensure AppRuntimeInitializer ran before Spring context");
        }
        var config = SQLiteMCWxAES256Config.getDefault()
                .withKey(toRawKey(key))
                .build();
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + AppPaths.dataFile().getAbsolutePath());
        return ds;
    }
}