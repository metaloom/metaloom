# Ideas

* JSON based
* Sequentially

## Script based

* Lua / Groovy
* Maybe similar to jenkins pipeline, github actions?


Example:
```yaml
name: Process assets

sources: [fs, s3]

steps:
  - name: Filter assets
    type: 'filter/…'
  - name: Generate fingerprint
    type: 'generate/fingerprint'
  - name: Hash assets
    type: 'generate/hash'   
```

Pros/Cons:

* Sequential node order
* No parallelism
* Enhanced human readability