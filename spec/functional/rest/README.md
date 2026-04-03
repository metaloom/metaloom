# REST / Server

## Requirements - Must

- Webhook support
- graphql endpoint is globally accessible and no longer scoped for namespaces (for now)

## Requirements - Maybe

- Full HTTP HEAD support in the API
- Manual tagging will create tags on-the fly. No need to create tags upfront
- Operations should in general not require the manual creation of a parent element. If a parent is required it should be able to be created on the fly.