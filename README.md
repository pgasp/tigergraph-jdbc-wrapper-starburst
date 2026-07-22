# TigerGraph JDBC Wrapper for Starburst

JDBC wrapper that bridges URL query parameters to driver Properties for the TigerGraph JDBC driver in Starburst Enterprise (SEP).

## Problem

The TigerGraph `RestppDriver` reads all connection properties (`graph`, `trustStore`, `trustStorePassword`, `trustStoreType`, `keyStore`, `token`, `queryTimeout`, …) from its `java.util.Properties` object — not from URL query parameters. Starburst Enterprise's Generic JDBC connector has no `connection-properties` passthrough; the only properties it sends to the driver are `connection-user` and `connection-password`.

This makes it impossible to pass `graph`, SSL settings, or any other TigerGraph driver parameter via a standard catalog configuration.

## Solution

This wrapper parses the URL query string, injects each key=value pair into the `Properties` object, and delegates to the real `RestppDriver` with the enriched properties. All TigerGraph driver parameters are embedded in `connection-url` — no JVM config changes required.

## Deployment

Place both JARs in the Generic JDBC plugin directory on all coordinator and worker nodes:

```
/usr/lib/starburst/plugin/generic-jdbc/
  ├── tg-jdbc-driver-1.x.jar
  └── tigergraph-jdbc-wrapper-1.0.0.jar
```

## Catalog configuration

```properties
connector.name=generic-jdbc
generic-jdbc.driver-class=io.starburst.jdbc.tigergraph.TigerGraphWrapperDriver
connection-url=jdbc:tg:https://HOST:14200?graph=MY_GRAPH&trustStore=/usr/lib/jvm/temurin-21/lib/security/cacerts&trustStorePassword=changeit&trustStoreType=JKS
connection-user=myuser
connection-password=mypassword
```

The TigerGraph certificate must be imported into the JVM `cacerts` beforehand:

```bash
keytool -importcert -alias tigergraph \
  -file tigergraph.crt \
  -keystore /usr/lib/jvm/temurin-21/lib/security/cacerts \
  -storepass changeit -noprompt
```

## Build

```bash
mvn clean package -DskipTests
# JAR: target/tigergraph-jdbc-wrapper-1.0.0.jar
```
