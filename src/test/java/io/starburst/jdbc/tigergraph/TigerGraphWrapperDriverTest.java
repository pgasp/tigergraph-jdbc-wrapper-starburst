package io.starburst.jdbc.tigergraph;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TigerGraphWrapperDriverTest {

    @Test
    void injectUrlParams_injectsAllQueryParams() {
        Properties props = new Properties();
        int count = TigerGraphWrapperDriver.injectUrlParams(
                "jdbc:tg:https://host:14200?graph=MY_GRAPH&trustStore=/path/cacerts&trustStorePassword=changeit&trustStoreType=JKS",
                props);

        assertEquals(4, count);
        assertEquals("MY_GRAPH",       props.getProperty("graph"));
        assertEquals("/path/cacerts",  props.getProperty("trustStore"));
        assertEquals("changeit",       props.getProperty("trustStorePassword"));
        assertEquals("JKS",            props.getProperty("trustStoreType"));
    }

    @Test
    void injectUrlParams_doesNotOverrideExistingProps() {
        Properties props = new Properties();
        props.setProperty("graph", "ALREADY_SET");
        int count = TigerGraphWrapperDriver.injectUrlParams(
                "jdbc:tg:https://host:14200?graph=NEW_VALUE&trustStore=/path/cacerts",
                props);

        assertEquals(1, count); // only trustStore injected
        assertEquals("ALREADY_SET", props.getProperty("graph"));
        assertEquals("/path/cacerts", props.getProperty("trustStore"));
    }

    @Test
    void injectUrlParams_noQueryString_returnsZero() {
        Properties props = new Properties();
        int count = TigerGraphWrapperDriver.injectUrlParams("jdbc:tg:https://host:14200", props);
        assertEquals(0, count);
        assertTrue(props.isEmpty());
    }

    @Test
    void injectUrlParams_emptyQueryString_returnsZero() {
        Properties props = new Properties();
        int count = TigerGraphWrapperDriver.injectUrlParams("jdbc:tg:https://host:14200?", props);
        assertEquals(0, count);
    }

    @Test
    void maskUrl_redactsPassword() {
        String masked = TigerGraphWrapperDriver.maskUrl(
                "jdbc:tg:https://host:14200?graph=G&trustStorePassword=secret&trustStore=/path");
        assertFalse(masked.contains("secret"));
        assertTrue(masked.contains("trustStorePassword=***"));
        assertTrue(masked.contains("graph=G"));
        assertTrue(masked.contains("trustStore=/path"));
    }

    @Test
    void maskUrl_nullSafe() {
        assertNull(TigerGraphWrapperDriver.maskUrl(null));
    }

    @Test
    void acceptsURL_acceptsJdbcTgPrefix() throws Exception {
        TigerGraphWrapperDriver wrapper = createWrapperWithoutDelegate();
        // acceptsURL is pure string logic — safe to call without real delegate
        assertTrue(wrapper.acceptsURL("jdbc:tg:https://host:14200"));
        assertTrue(wrapper.acceptsURL("jdbc:tg:http://host:14200"));
        assertFalse(wrapper.acceptsURL("jdbc:postgresql://host:5432/db"));
        assertFalse(wrapper.acceptsURL(null));
    }

    /**
     * Creates a wrapper instance bypassing the static initializer (which requires
     * the real TigerGraph driver JAR in the classpath). Used only for unit tests
     * that exercise pure logic (acceptsURL, maskUrl, injectUrlParams).
     */
    private TigerGraphWrapperDriver createWrapperWithoutDelegate() {
        try {
            var ctor = TigerGraphWrapperDriver.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            // Static init will fail without the TigerGraph JAR — expected in unit tests.
            // Return a fresh instance via unsafe allocation if static init is the blocker.
            throw new RuntimeException("Cannot instantiate wrapper without TigerGraph driver JAR", e);
        }
    }
}
