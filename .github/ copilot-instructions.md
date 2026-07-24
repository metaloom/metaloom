
# Project Folders

* spec              - Contains specification documents
* bom               - Bill Of Material Maven Project
* loom-shared       - Shared Rest Models / API
* cortex            - Contains the MetaLoom // Cortex - Media Processing codebase
* loom              - Contains the MetaLoom // Loom - Backend

* loom-app          - Elektron app that contains the loom-ui
* loom-ui           - Frontend
* loom-client       - Java Clients
* loom-test-env     - Shared test data
* integration-test  - IT 
* cortex-custom - Example project which shows how the cortex part can be extended

# Test Notes

* After database changes it is required to execute the `io.metaloom.loom.test.PoolSetupRunner` from the `loom-fixture` project. This is used to prepare the test database with test data. All database tests will utilize the data that is being created during this stage.
* Run `io.metaloom.loom.core.endpoint.test.CombinedEndpointTest` to verify that endpoint interaction works
* Each database test will obtain a fresh database from the pool