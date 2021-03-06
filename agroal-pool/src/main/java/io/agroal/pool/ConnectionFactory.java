// Copyright (C) 2017 Red Hat, Inc. and individual contributors as indicated by the @author tags.
// You may not use this file except in compliance with the Apache License, Version 2.0.

package io.agroal.pool;

import io.agroal.api.AgroalDataSourceListener;
import io.agroal.api.configuration.AgroalConnectionFactoryConfiguration;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import io.agroal.pool.util.PropertyInjector;
import io.agroal.pool.util.XAConnectionAdaptor;

import javax.sql.XAConnection;
import java.lang.reflect.InvocationTargetException;
import java.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

import static io.agroal.pool.util.ListenerHelper.fireOnWarning;

/**
 * @author <a href="lbarreiro@redhat.com">Luis Barreiro</a>
 */
public final class ConnectionFactory {

    private static final String URL_PROPERTY_NAME = "url";
    private static final String USER_PROPERTY_NAME = "user";
    private static final String PASSWORD_PROPERTY_NAME = "password";

    private final AgroalConnectionFactoryConfiguration configuration;
    private final AgroalDataSourceListener[] listeners;
    private final Properties jdbcProperties;
    private final Mode factoryMode;

    // these are the sources for connections, that will be used depending on the mode
    private java.sql.Driver driver;
    private javax.sql.DataSource dataSource;
    private javax.sql.XADataSource xaDataSource;

    public ConnectionFactory(AgroalConnectionFactoryConfiguration configuration, AgroalDataSourceListener... listeners) {
        this.configuration = configuration;
        this.listeners = listeners;
        this.jdbcProperties = new Properties();
        configuration.jdbcProperties().forEach( jdbcProperties::put );

        setupSecurity( configuration );

        this.factoryMode = Mode.fromClass( configuration.connectionProviderClass() );
        switch ( factoryMode ) {
            case XA_DATASOURCE:
                setupXA();
                break;
            case DATASOURCE:
                setupDataSource();
                break;
            case DRIVER:
                setupDriver();
                break;
        }
    }

    private void setupXA() {
        try {
            this.xaDataSource = configuration.connectionProviderClass().asSubclass( javax.sql.XADataSource.class ).newInstance();
        } catch ( IllegalAccessException | InstantiationException e ) {
            throw new RuntimeException( "Unable to instantiate XADataSource", e );
        }
        PropertyInjector injector = new PropertyInjector( configuration.connectionProviderClass() );

        if ( configuration.jdbcUrl() != null && !configuration.jdbcUrl().isEmpty() ) {
            injectProperty( injector, xaDataSource, URL_PROPERTY_NAME, configuration.jdbcUrl() );
        }

        injectJdbcProperties( injector, xaDataSource );
    }

    private void setupDataSource() {
        try {
            this.dataSource = configuration.connectionProviderClass().asSubclass( javax.sql.DataSource.class ).newInstance();
        } catch ( IllegalAccessException | InstantiationException e ) {
            throw new RuntimeException( "Unable to instantiate DataSource", e );
        }
        PropertyInjector injector = new PropertyInjector( configuration.connectionProviderClass() );

        if ( configuration.jdbcUrl() != null && !configuration.jdbcUrl().isEmpty() ) {
            injectProperty( injector, dataSource, URL_PROPERTY_NAME, configuration.jdbcUrl() );
        }

        injectJdbcProperties( injector, dataSource );
    }

    private void setupDriver() {
        if ( configuration.connectionProviderClass() == null ) {
            try {
                this.driver = java.sql.DriverManager.getDriver( configuration.jdbcUrl() );
            } catch ( SQLException sql ) {
                throw new RuntimeException( "Unable to get java.sql.Driver from DriverManager", sql );
            }
        } else {
            try {
                this.driver = configuration.connectionProviderClass().asSubclass( java.sql.Driver.class ).newInstance();
            } catch ( IllegalAccessException | InstantiationException e ) {
                throw new RuntimeException( "Unable to instantiate java.sql.Driver", e );
            }
        }
    }

    private void injectProperty(PropertyInjector injector, Object target, String propertyName, String propertyValue) {
        try {
            injector.inject( target, propertyName, propertyValue );
        } catch ( IllegalArgumentException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e ) {
            fireOnWarning( listeners, "Ignoring property '" + propertyName + "': " + e.getMessage() );
        }
    }

    private void injectJdbcProperties(PropertyInjector injector, Object target) {
        boolean ignoring = false;
        for ( String propertyName : jdbcProperties.stringPropertyNames() ) {
            try {
                injector.inject( target, propertyName, jdbcProperties.getProperty( propertyName ) );
            } catch ( IllegalArgumentException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e ) {
                fireOnWarning( listeners, "Ignoring property '" + propertyName + "': " + e.getMessage() );
                ignoring = true;
            }
        }
        if ( ignoring ) {
            fireOnWarning( listeners, "Available properties " + Arrays.toString( injector.availableProperties().toArray() ) );
        }
    }

    // --- //

    private void setupSecurity(AgroalConnectionFactoryConfiguration configuration) {
        Principal principal = configuration.principal();

        if ( principal == null ) {
            // skip!
        } else if ( principal instanceof NamePrincipal ) {
            jdbcProperties.put( USER_PROPERTY_NAME, principal.getName() );
        }

        // Add other principal types here

        else {
            throw new IllegalArgumentException( "Unknown Principal type: " + principal.getClass().getName() );
        }

        for ( Object credential : configuration.credentials() ) {
            if ( credential instanceof SimplePassword ) {
                jdbcProperties.put( PASSWORD_PROPERTY_NAME, ( (SimplePassword) credential ).getWord() );
            }

            // Add other credential types here

            else {
                throw new IllegalArgumentException( "Unknown Credential type: " + credential.getClass().getName() );
            }
        }
    }

    // --- //

    public XAConnection createConnection() throws SQLException {
        switch ( factoryMode ) {
            case DRIVER:
                return new XAConnectionAdaptor( connectionSetup( driver.connect( configuration.jdbcUrl(), jdbcProperties ) ) );
            case DATASOURCE:
                return new XAConnectionAdaptor( connectionSetup( dataSource.getConnection() ) );
            case XA_DATASOURCE:
                return xaConnectionSetup( xaDataSource.getXAConnection() );
            default:
                throw new SQLException( "Unknown connection factory mode" );
        }
    }

    private Connection connectionSetup(Connection connection) throws SQLException {
        connection.setAutoCommit( configuration.autoCommit() );
        if ( configuration.jdbcTransactionIsolation().isDefined() ) {
            connection.setTransactionIsolation( configuration.jdbcTransactionIsolation().level() );
        }
        if ( configuration.initialSql() != null && !configuration.initialSql().isEmpty() ) {
            try ( Statement statement = connection.createStatement() ) {
                statement.execute( configuration.initialSql() );
            }
        }
        return connection;
    }

    private XAConnection xaConnectionSetup(XAConnection xaConnection) throws SQLException {
        if ( xaConnection.getXAResource() == null ) {
            // this ensures that XAConnections are not processed as non-XA connections by the pool
            throw new SQLException( "null XAResource from XADataSource" );
        }
        connectionSetup( xaConnection.getConnection() );
        return xaConnection;
    }

    // --- //

    private enum Mode {

        DRIVER, DATASOURCE, XA_DATASOURCE;

        private static Mode fromClass(Class<?> providerClass) {
            if ( providerClass == null ) {
                return DRIVER;
            } else if ( javax.sql.XADataSource.class.isAssignableFrom( providerClass ) ) {
                return XA_DATASOURCE;
            } else if ( javax.sql.DataSource.class.isAssignableFrom( providerClass ) ) {
                return DATASOURCE;
            } else if ( java.sql.Driver.class.isAssignableFrom( providerClass ) ) {
                return DRIVER;
            } else {
                throw new IllegalArgumentException( "Unable to create ConnectionFactory from providerClass " + providerClass.getName() );
            }
        }
    }
}
