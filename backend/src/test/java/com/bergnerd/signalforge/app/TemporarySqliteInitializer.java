package com.bergnerd.signalforge.app;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class TemporarySqliteInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static volatile Path databasePath;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        try {
            databasePath = Files.createTempFile("signalforge-integration-", ".db");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create integration database", exception);
        }

        TestPropertyValues.of(
                "spring.datasource.url=jdbc:sqlite:" + databasePath,
                "signalforge.test.database-path=" + databasePath,
                "signalforge.llm.mock=true",
                "signalforge.massive.api-key="
        ).applyTo(context);

        context.addBeanFactoryPostProcessor(beanFactory -> {
            DisposableBean cleanup = () -> deleteDatabaseFiles(databasePath);
            beanFactory.registerSingleton("testDatabaseCleanup", cleanup);
            ((DefaultListableBeanFactory) beanFactory).registerDisposableBean("testDatabaseCleanup", cleanup);
            BeanDefinition dataSource = beanFactory.getBeanDefinition("dataSource");
            dataSource.setDependsOn("testDatabaseCleanup");
        });
    }

    static void deleteDatabaseFiles(Path path) throws IOException {
        Files.deleteIfExists(Path.of(path + "-wal"));
        Files.deleteIfExists(Path.of(path + "-shm"));
        Files.deleteIfExists(path);
    }
}
