# SSO

## Usecase

* User Login (frontend) -> SSO -> Auth (Gen JWT) -> pass along to loom
* Loom -> Map token info to content + authenticate using in-memory SSO user + ad-hoc permissions
* Q: How to map creator/editor information for in-memory SSO user?
* Q: How to prevent other users (B) to access content of user A?

## Requirements

- Direct native keycloak, auth0 support