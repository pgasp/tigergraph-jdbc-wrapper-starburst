# TigerGraph JDBC Wrapper for Starburst

Thin JDBC wrapper that enables TigerGraph SSL connections from Starburst Enterprise (SEP) Generic JDBC connector.

## Problem

The TigerGraph `RestppDriver` reads SSL properties (`trustStore`, `trustStorePassword`, `trustStoreType`, `keyStore`, …) from the JDBC `java.util.Properties` object — not from URL query parameters. In HTTPS mode it throws immediately if neither `trustStore` nor `keyStore` is present in Properties.

Starburst Enterprise's Generic JDBC connector has no `connection-properties` passthrough. The only input it sends to the driver Properties object is `connection-user` and `connection-password`.

## Solution

This wrapper parses URL query parameters and injects them into the Properties object before delegating to the real `RestppDriver`. All SSL config is embedded in `connection-url` — no JVM config changes required.

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
generic-jdbc.driver-class=io.starburst.jdbc.tigergraph.TigerGraphSSLWrapper
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
