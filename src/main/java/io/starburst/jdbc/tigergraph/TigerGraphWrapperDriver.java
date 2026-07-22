package io.starburst.jdbc.tigergraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

/**
 * JDBC wrapper for the TigerGraph JDBC driver (tg-jdbc-driver).
 *
 * The TigerGraph RestppDriver reads all connection properties (graph, trustStore,
 * trustStorePassword, trustStoreType, keyStore, token, queryTimeout, …) from its
 * java.util.Properties object — not from URL query parameters. Starburst Enterprise's
 * Generic JDBC connector only passes connection-user and connection-password as driver
 * Properties; there is no connection-properties passthrough mechanism.
 *
 * This wrapper bridges the gap: it accepts a standard jdbc:tg: URL with any parameters
 * embedded as query string key=value pairs, parses them, injects them into the
 * Properties object, and delegates to the real RestppDriver with the enriched Properties.
 * This makes all TigerGraph driver parameters configurable from the catalog
 * connection-url — graph name, SSL settings, timeouts, or any other driver option.
 *
 * Sensitive values (password, trustStorePassword, keyStorePassword, token) are
 * redacted in all log output.
 *
 * SEP catalog-values.yaml example:
 *   connector.name=generic-jdbc
 *   generic-jdbc.driver-class=io.starburst.jdbc.tigergraph.TigerGraphWrapperDriver
 *   connection-url=jdbc:tg:https://host:14200?graph=MY_GRAPH&trustStore=/path/to/cacerts&trustStorePassword=changeit&trustStoreType=JKS
 *   connection-user=myuser
 *   connection-password=mypassword
 */
public class TigerGraphWrapperDriver implements Driver {

    private static final Logger log = LoggerFactory.getLogger(TigerGraphWrapperDriver.class);

    private static final String WRAPPER_VERSION = "1.0.2";
    private static final String TG_DRIVER_CLASS = "com.tigergraph.jdbc.Driver";

    private static final Driver TG_DRIVER;

    static {
        log.info("v{} initializing — loading TigerGraph driver class: {}", WRAPPER_VERSION, TG_DRIVER_CLASS);
        Driver driver = null;
        try {
            driver = (Driver) Class.forName(TG_DRIVER_CLASS)
                    .getDeclaredConstructor()
                    .newInstance();
            log.info("v{} TigerGraph driver class loaded OK — majorVersion={} minorVersion={} jdbcCompliant={}",
                    WRAPPER_VERSION,
                    driver.getMajorVersion(),
                    driver.getMinorVersion(),
                    driver.jdbcCompliant());
        } catch (ClassNotFoundException e) {
            log.error("FATAL: TigerGraph JDBC driver class not found: {} — ensure tg-jdbc-driver.jar is in /usr/lib/starburst/plugin/generic-jdbc/",
                    TG_DRIVER_CLASS, e);
            throw new ExceptionInInitializerError(
                    "TigerGraph JDBC driver (tg-jdbc-driver.jar) not found in classpath: " + e.getMessage());
        } catch (Exception e) {
            log.error("FATAL: failed to instantiate TigerGraph driver class: {}", TG_DRIVER_CLASS, e);
            throw new ExceptionInInitializerError(e);
        }
        TG_DRIVER = driver;
        try {
            DriverManager.registerDriver(new TigerGraphWrapperDriver());
            log.info("v{} wrapper registered with DriverManager OK", WRAPPER_VERSION);
        } catch (SQLException e) {
            log.error("FATAL: failed to register wrapper with DriverManager", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        log.debug("connect() called — url={}", maskUrl(url));

        if (!acceptsURL(url)) {
            log.debug("connect() rejected (not a jdbc:tg: URL): {}", maskUrl(url));
            return null;
        }

        if (info == null) {
            log.debug("connect() Properties object is null — creating empty Properties");
            info = new Properties();
        }

        log.debug("connect() Properties before injection: {}", maskProps(info));
        int injected = injectUrlParams(url, info);
        log.info("connect() url={} injected={} effectiveProps={}", maskUrl(url), injected, maskProps(info));

        log.debug("connect() delegating to TigerGraph driver: {}", TG_DRIVER_CLASS);
        Connection conn;
        try {
            conn = TG_DRIVER.connect(url, info);
        } catch (SQLException e) {
            log.error("connect() FAILED — TigerGraph driver threw SQLException: [SQLState={}] [ErrorCode={}] {}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("connect() FAILED — unexpected exception from TigerGraph driver: {}", e.getMessage(), e);
            throw new SQLException("TigerGraph driver threw unexpected exception: " + e.getMessage(), e);
        }

        if (conn == null) {
            log.warn("connect() → null — TigerGraph driver returned null for url={} (URL not accepted by delegate after injection)",
                    maskUrl(url));
            return null;
        }

        log.info("connect() SUCCESS — connection established to {}", maskUrl(url));
        return conn;
    }

    /**
     * Parses query parameters from the JDBC URL and injects them into the Properties
     * object if the key is not already present. Returns the number of params injected.
     *
     * URL format: jdbc:tg:https://host:14200?graph=MY_GRAPH&trustStore=/path&trustStorePassword=xxx
     */
    static int injectUrlParams(String url, Properties info) {
        int qIdx = url.indexOf('?');
        if (qIdx < 0 || qIdx >= url.length() - 1) {
            log.debug("injectUrlParams: no query string in URL");
            return 0;
        }

        String query = url.substring(qIdx + 1);
        int injected = 0;

        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length != 2 || kv[0].isEmpty()) {
                log.debug("injectUrlParams: skipping malformed param '{}'", param);
                continue;
            }
            try {
                String key   = URLDecoder.decode(kv[0], "UTF-8");
                String value = URLDecoder.decode(kv[1], "UTF-8");
                if (info.containsKey(key)) {
                    log.debug("injectUrlParams: key='{}' already in Properties — not overriding", key);
                } else {
                    info.setProperty(key, value);
                    log.debug("injectUrlParams: injected key='{}' value='{}'", key, maskValue(key, value));
                    injected++;
                }
            } catch (UnsupportedEncodingException e) {
                // UTF-8 is always supported per Java spec — cannot happen
                log.warn("injectUrlParams: UTF-8 decode failed for param '{}': {}", param, e.getMessage());
            }
        }

        log.debug("injectUrlParams: {} param(s) injected from URL query string", injected);
        return injected;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        boolean accepts = url != null && url.startsWith("jdbc:tg:");
        log.debug("acceptsURL('{}') → {}", maskUrl(url), accepts);
        return accepts;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        log.debug("getPropertyInfo() url={}", maskUrl(url));
        try {
            DriverPropertyInfo[] result = TG_DRIVER.getPropertyInfo(url, info);
            log.debug("getPropertyInfo() → {} propert(ies)", result != null ? result.length : 0);
            return result;
        } catch (Exception e) {
            log.error("getPropertyInfo() FAILED: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not used");
    }

    /** Redacts password-like values from a JDBC URL for safe log output. */
    static String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("(?i)((?:password|trustStorePassword|keyStorePassword|token)=)[^&]+", "$1***");
    }

    /** Redacts sensitive keys from a Properties object for safe log output. */
    static String maskProps(Properties props) {
        if (props == null) return "null";
        Properties redacted = new Properties();
        redacted.putAll(props);
        for (String key : new String[]{"password", "trustStorePassword", "keyStorePassword", "token"}) {
            if (redacted.containsKey(key)) {
                redacted.setProperty(key, "***");
            }
        }
        return redacted.toString();
    }

    /** Redacts a single value if its key is considered sensitive. */
    private static String maskValue(String key, String value) {
        if (key == null) return value;
        switch (key.toLowerCase()) {
            case "password":
            case "truststorepassword":
            case "keystorepassword":
            case "token":
                return "***";
            default:
                return value;
        }
    }
}
