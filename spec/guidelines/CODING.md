## REST 
* REST API paths that have methods must always be plural (e.g. /chat-sessions, /sessions)
* REST API Implementations must always be covered by a ResourceEndpoint test (e.g. UserEndpointTest)
* Add permission testcases that assert the API handles fine granular permissions

## DAO
* DAO Implementation must always be covered by tests
* DAO Implementations for delete must be covered by delete cascade tests that assert that only the targeted elements get deleted via the cascade.

## Docs
* New customer facing features must be included in the website/content/english/docs area
 - Don't mention spec files
 - Don't include internal coding references
 - Keep the website docs customer facing

## Demo
* New features must have meaningful default demo data (see DemoDatabaseInitializer)

## Spec
* Changing a feature must also include an update to the corresponding spec file to keep the internal AI coding guides in-sync.
