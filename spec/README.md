# Loom Architectural Design Specification

This repository contains design documents for the Loom project.

## Goals

The loom project should provide a software solution which can be used to manage and organize assets and connected metadata in a supervised or unsupervised way.

It should provide processes to categorize media assets and handle tasks such as:

* Thumbnail generation
* Deduplication
* Processing (Encoding,redistibution)
* Event Handling
* Video fingerprinting
* Whitelisting/Blacklisting
* Image Annotation / Labeling

## Core Concepts

* Easy permission system
* Hibernate / JOOQ
* Direct Keycloak support
* S3 Support
* OpenAPI

## Differences

- binary assets are now managed as a dedicated entity
- API tokens have dedicated permissions
- Users, Groups, Roles, Namespaces can now have arbitrary properties

## Ideas

- Auditing
- Waste Bin Management
- Add nesting flag to models to control depth of allowed nesting? May be confusing when nesting fails due to hard limitation.
- Support models for group, roles, user, namespace as well?
- Time scheduled publishing?
- Access history?
- Group, User, Role Content References
- Sharing to customers
- Re-encoding in different quality levels
- Tagging of areas, timecodes (Scrollview of created tags/annotations)
- SSO integration
- Ticket Tracker integration / Review tool integration

## Phases

### Phase 0 (MVP)

- Assets + Pipeline
- UI for assets / pipeline / asset views
- CLI

### Phase 1

- Search
- ACL

### Phase 2

- Contents / Models

## Links

* https://github.com/orgs/metaloom
* https://metaloom.io
* https://hub.docker.com/orgs/metaloom
