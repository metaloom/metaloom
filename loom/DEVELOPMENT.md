# Development

Many tests require the use of a database harness. The [testdatabase-provider](https://github.com/metaloom/testdatabase-provider) toolchain is used to provide testdatabases.

1. Startup a testprovider instance which will manage test databases leases and postgres DB

* container-compose.yml:

```bash
version: '3.6'
services:
    db:
        container_name: 'testprovider-postgresql'
        hostname: 'postgresql'
        image: 'postgres:13.2'
        ports:
            - '15432:5432/tcp'
        environment:
            - POSTGRES_USER=sa
            - POSTGRES_PASSWORD=sa
            - POSTGRES_DB=test
            - PGDATA=/live/pgdata
        volumes:
            - type: tmpfs
              target: /live
              tmpfs:
                size: 10g
        command: [ "postgres", "-c", "fsync=off" ]
        restart: unless-stopped
    provider:
        container_name: 'testprovider-provider'
        hostname: 'provider'
        image: 'metaloom/testdatabase-provider:0.1.3'
        ports:
            - '7543:8080/tcp'
        environment:
         # Admin DB
         - TESTDATABASE_PROVIDER_DATABASE_DBNAME_KEY=test
         # Public DB connection details
         - TESTDATABASE_PROVIDER_DATABASE_HOST=saturn
         - TESTDATABASE_PROVIDER_DATABASE_PORT=15432
         # Internal DB connection details (inter container communication)
         - TESTDATABASE_PROVIDER_DATABASE_INTERNAL_HOST=postgresql
         - TESTDATABASE_PROVIDER_DATABASE_INTERNAL_PORT=5432
         - TESTDATABASE_PROVIDER_DATABASE_USERNAME=sa
         - TESTDATABASE_PROVIDER_DATABASE_PASSWORD=sa
         # Default pool settings
         - TESTDATABASE_PROVIDER_POOL_MAXIMUM=40
         - TESTDATABASE_PROVIDER_POOL_MINIMUM=20
         - TESTDATABASE_PROVIDER_POOL_INCREMENT=10
        restart: unless-stopped
```
2. Run `PoolSetupActionTest` to create an initial template database.

3. Run `UserEndpointTest`to ensure that the toolchain works.