package com.consense.config;

import db.migration.V28__h2_source_document_clobs;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit maintenance path for Hibernate-created H2 files that predate CLOB mappings. */
@Configuration(proxyBeanMethods = false)
@Profile("h2")
public class H2SourceClobUpgradeConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consense.h2.source-clob-upgrade", havingValue = "true")
    HibernatePropertiesCustomizer sourceClobUpgrade(DataSource dataSource) {
        return properties -> {
            try (Connection connection = dataSource.getConnection()) {
                if (!"H2".equals(connection.getMetaData().getDatabaseProductName()))
                    throw new IllegalStateException("H2 source_document CLOB upgrade requires an H2 datasource");
                // shortcut: H2 may commit each ALTER separately; enable only after a closed clone passes verification.
                V28__h2_source_document_clobs.upgradeExisting(connection);
            } catch (Exception error) {
                throw new IllegalStateException("H2 source_document CLOB upgrade failed before Hibernate startup", error);
            }
        };
    }
}
