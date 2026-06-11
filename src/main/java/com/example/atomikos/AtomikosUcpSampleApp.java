package com.example.atomikos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Spring Boot entry point.
 *
 * <p>We exclude {@link DataSourceAutoConfiguration} because our datasource is
 * built entirely in {@link com.example.atomikos.config.DataSourceConfig}: an
 * Oracle UCP {@code PoolXADataSource} wrapped in Atomikos'
 * {@code AtomikosNonPoolingDataSourceBean}.  Spring Boot must not attempt to
 * create a second, HikariCP-backed datasource from {@code application.yml}
 * URL/username/password properties.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AtomikosUcpSampleApp {

    public static void main(String[] args) {
        SpringApplication.run(AtomikosUcpSampleApp.class, args);
    }
}
