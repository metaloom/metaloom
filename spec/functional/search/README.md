# Search

## Requirements

- Search support is limited to contents (for now)
- Elasticsearch should be used for the default search 
- The search is mandatory for operation and can not be switched off (TBD)
- A bucket mechanism should be used to provide a way to synchronize the Loom data with the elasticsearch indices.
  Entities in Loom are assigned a random Id. The synch operation can thus create a number batches which is depended on the number of entities in the Loom system. This way the synchronization operation can be processed in multiple chunks.
