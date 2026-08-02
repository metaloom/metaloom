# 1.0.0-SNAPSHOT

* Added the `gdrive-source` and `onedrive-source` pipeline node kinds: differential ingest from
  Google Drive, OneDrive and SharePoint document libraries. Both use the provider's change feed, so
  a re-run over an unchanged drive costs a single request, and both detect renames and moves rather
  than reporting them as a deletion plus a new file. Credentials are worker-level
  (`CORTEX_GDRIVE_*`, `CORTEX_ONEDRIVE_*`); a kind is advertised only when that provider is
  configured. No shared media mount is required — files are fetched lazily by whichever worker runs
  a node task against them.
* Initial public release