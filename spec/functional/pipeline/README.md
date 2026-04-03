# Pipeline

**Pipelines** are used to define automatic **Asset** and **Content** processing.

## Concept / Usecase

A pipeline consists of multiple nodes which can be connected via the UI by the user. A pipeline might scan a folder and process new assets by hashing and filtering them.

TODO: Decide how to use pipelines for **Contents**.

## Workers

IO intensive operations will be executed by a worker. One worker is embedded into the loom server. Additional workers can be launched as additional services.

## Pipeline Nodes

[See here](NODES.md)

## Requirements

- Support for asset fingerprinting
- Support for asset thumbnail generation


