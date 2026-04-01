<h1 align="center">Loom Java Client </h3>

This project contains a java client for [MetaLoom - Loom // Nexus](https://metaloom.io/). The client supports HTTP and gRPC transport in either blocking or non-blocking fashion. For async operation a `Future` or RxJava3 based API can be used.

<br />

## Maven

```xml
<dependency>
	<groupId>io.metaloom.loom.client</groupId>
	<artifactId>loom-client-grpc</artifactId>
	<version>1.0.0-SNAPSHOT</version>
</dependency>
```

or for the HTTP client

```xml
<dependency>
	<groupId>io.metaloom.loom.client</groupId>
	<artifactId>loom-client-rest</artifactId>
	<version>1.0.0-SNAPSHOT</version>
</dependency>
```