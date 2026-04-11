package com.app.common.config;

import com.app.common.exception.AppException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;
import org.sqlite.mc.SQLiteMCWxAES256Config;

import javax.sql.DataSource;

@Configuration
public class SQLiteConfig {

    @Bean
    public DataSource dataSource() {
        if (!AppContext.isDbEncryptionEnabled()) {
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + AppPaths.dataFile().getAbsolutePath());
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
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + AppPaths.dataFile().getAbsolutePath());
        return ds;
    }
}
