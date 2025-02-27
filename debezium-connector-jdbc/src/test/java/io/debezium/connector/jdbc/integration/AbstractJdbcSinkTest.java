/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.jdbc.integration;

import static org.fest.assertions.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.lettercase.LetterCase;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mchange.v2.c3p0.DataSources;

import io.debezium.bindings.kafka.KafkaDebeziumSinkRecord;
import io.debezium.connector.jdbc.JdbcSinkConnector;
import io.debezium.connector.jdbc.JdbcSinkConnectorConfig;
import io.debezium.connector.jdbc.JdbcSinkTaskTestContext;
import io.debezium.connector.jdbc.junit.jupiter.Sink;
import io.debezium.connector.jdbc.util.RandomTableNameGenerator;
import io.debezium.sink.naming.CollectionNamingStrategy;
import io.debezium.sink.naming.DefaultCollectionNamingStrategy;

/**
 * Abstract JDBC Sink Connector integration test.
 *
 * @author Chris Cranford
 */
public abstract class AbstractJdbcSinkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractJdbcSinkTest.class);

    private final Sink sink;
    private final RandomTableNameGenerator randomTableNameGenerator = new RandomTableNameGenerator();
    private final CollectionNamingStrategy collectionNamingStrategy = new DefaultCollectionNamingStrategy();

    private JdbcSinkConnector sinkConnector;
    private SinkTask sinkTask;
    private DataSource dataSource;

    public AbstractJdbcSinkTest(Sink sink) {
        this.sink = sink;
    }

    @AfterEach
    public void afterEach() {
        stopSinkConnector();

        if (dataSource != null) {
            try {
                DataSources.destroy(DataSources.pooledDataSource(dataSource));
                LOGGER.info("Closed data source");
            }
            catch (SQLException e) {
                LOGGER.error("Failed to close data source", e);
            }
        }
    }

    protected Sink getSink() {
        return sink;
    }

    /**
     * Returns the default, basic sink connector configuration to talk to the database instance
     * that was started by the TestContainers framework.
     */
    protected Map<String, String> getDefaultSinkConfig() {
        final Map<String, String> config = new LinkedHashMap<>();
        // Explicitly use the Jdbc URL from the sink as some databases may need to manipulate this
        // due to how instance vs databases are handled within the container, i.e. SQL Server.
        config.put(JdbcSinkConnectorConfig.CONNECTION_URL, sink.getJdbcUrl());
        config.put(JdbcSinkConnectorConfig.CONNECTION_USER, sink.getUsername());
        config.put(JdbcSinkConnectorConfig.CONNECTION_PASSWORD, sink.getPassword());
        return config;
    }

    protected Map<String, String> getConfig(Map<String, String> properties) {
        final Map<String, String> config = getDefaultSinkConfig();
        config.putAll(properties);
        return config;
    }

    /**
     * Returns a {@link DataSource} to access the sink connector's database instance started by the
     * TestContainers framework.
     */
    protected DataSource dataSource() {
        try {
            if (dataSource == null) {
                LOGGER.info("Creating data source");
                final Map<String, String> config = getDefaultSinkConfig();
                dataSource = DataSources.unpooledDataSource(
                        config.get(JdbcSinkConnectorConfig.CONNECTION_URL),
                        config.get(JdbcSinkConnectorConfig.CONNECTION_USER),
                        config.get(JdbcSinkConnectorConfig.CONNECTION_PASSWORD));
            }
            return dataSource;
        }
        catch (SQLException e) {
            throw new RuntimeException("Failed to create data source", e);
        }
    }

    protected AssertDbConnection assertDbConnection() {
        return AssertDbConnectionFactory.of(dataSource()).create();
    }

    protected AssertDbConnection assertDbConnection(LetterCase tableLetterCase, LetterCase columnLetterCase, LetterCase primaryKeyLetterCase) {
        return AssertDbConnectionFactory.of(dataSource())
                .letterCase(tableLetterCase, columnLetterCase, primaryKeyLetterCase)
                .create();
    }

    /**
     * Start the sink connector with the provided properties
     *
     * @param properties the sink connector's configuration properties
     */
    protected void startSinkConnector(Map<String, String> properties) {
        sinkConnector = new JdbcSinkConnector();
        sinkConnector.start(properties);
        try {
            sinkTask = (SinkTask) sinkConnector.taskClass().getConstructor().newInstance();

            // Initialize sink task with a mock context
            sinkTask.initialize(new JdbcSinkTaskTestContext(properties));
            sinkTask.start(properties);
        }
        catch (Exception e) {
            sinkTask = null;
            sinkConnector = null;
            throw new RuntimeException(e);
        }
    }

    /**
     * Stops the sink connector.
     */
    protected void stopSinkConnector() {
        if (sinkConnector != null) {
            if (sinkTask != null) {
                sinkTask.stop();
                sinkTask = null;
            }
            sinkConnector.stop();
            sinkConnector = null;
        }
    }

    /**
     * Consumes the provided {@link SinkRecord} by the JDBC sink connector task.
     */
    protected void consume(KafkaDebeziumSinkRecord record) {
        if (record != null) {
            consume(Collections.singletonList(record));
        }
    }

    /**
     * Consumes the provided collection of {@link SinkRecord} by the JDBC sink connector task.
     */
    protected void consume(List<KafkaDebeziumSinkRecord> records) {
        List<SinkRecord> kafkaRecords = records.stream().map(KafkaDebeziumSinkRecord::getOriginalKafkaRecord).toList();
        sinkTask.put(kafkaRecords);
    }

    /**
     * Returns a random table name that can be used by the test.
     */
    protected String randomTableName() {
        return randomTableNameGenerator.randomName();
    }

    protected String destinationTableName(KafkaDebeziumSinkRecord record) {
        // todo: pass the configuration in from the test
        final JdbcSinkConnectorConfig config = new JdbcSinkConnectorConfig(getDefaultSinkConfig());
        return sink.formatTableName(collectionNamingStrategy.resolveCollectionName(record, config.getCollectionNameFormat()));
    }

    /**
     * Returns a constructed topic name based on the prefix, schema, and table names.
     */
    protected String topicName(String prefix, String schemaName, String tableName) {
        return prefix + "." + schemaName + "." + tableName;
    }

    protected void assertSinkConnectorIsRunning() {
        assertThat(sinkConnector).as("Sink connector is not currently running").isNotNull();
    }

}
