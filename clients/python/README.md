# loom-client

Python client for the MetaLoom Loom REST API.

Synchronous, built on the standard library, **no third-party dependencies**. It mirrors
the Java client method for method, so anything you can do from the JVM you can do here.

```python
from loom_client import LoomClient

with LoomClient(host="localhost", port=8092) as client:
    client.authenticate("admin", "finger")

    print(client.rest_info().body().version)

    for user in client.list_users().iter():
        print(user.username)
```

## Install

```bash
pip install ./clients/python
```

Requires Python 3.10 or newer. Not published to PyPI yet.

For development, `./setup.sh` creates a `.venv` and installs the package in editable
mode along with `ruff`.

## Using it

### Requests are built, then sent

Every method returns a request that has not gone anywhere yet. That is what leaves room
to attach paging and filtering afterwards:

```python
users = client.list_users().limit(50).sort("username").body()
```

Two ways to finish:

```python
user = client.load_user(uuid).body()  # the parsed model
response = client.load_user(uuid).execute()  # .body, .status, .headers
```

### Authentication

```python
client = LoomClient(host="localhost", port=8092)
client.authenticate("admin", "finger")  # log in and keep the token

client.set_token(existing_api_token)  # or use a token from /tokens
LoomClient(host="...", token=existing_api_token)

LoomClient.from_env()  # LOOM_HOST, LOOM_PORT, LOOM_TOKEN, ...
```

### Paging

List routes page by seek cursor. `iter()` follows it for you:

```python
for asset in client.list_assets().iter(page_size=100):
    print(asset.uuid)
```

Or drive it by hand:

```python
page = client.list_assets().limit(100).body()
print(page.metainfo.total_count)
next_page = client.list_assets().limit(100).from_(page.metainfo.last_uuid).body()
```

### Filtering

```python
from loom_client import eq, gte

client.list_users().filter(eq("username", "joedoe")).body()
client.list_assets().filter(gte("size", "1MB")).filter(eq("status", "DONE")).body()
```

Filters repeat and the server ANDs them.

### Assets, by UUID or by content

An asset is addressable either way, and every asset method takes both:

```python
client.load_asset("3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
client.load_asset("cf83e1357eefb8bd...")  # SHA-512 of the binary
```

### Uploads and downloads

```python
asset = client.upload_asset("/path/to/photo.jpg", library_uuid=library.uuid).body()

with client.download_asset_binary(asset.uuid).body() as binary:
    binary.save("/tmp/photo.jpg")
```

Downloads stream and must be closed — use them as a context manager. Uploads are held
in memory, capped at 64 MiB by `loom_client.multipart.MAX_UPLOAD_BYTES`.

### Errors

Every non-2xx response raises a subclass chosen by status code:

```python
from loom_client import LoomNotFoundError

try:
    client.load_user(uuid).body()
except LoomNotFoundError as e:
    print(e.message)  # the server's explanation
    print(e.status)  # 404
```

`LoomError` is the base of everything; `LoomConnectionError` means no response arrived
at all.

### Search

Search has its own parameters, separate from the list-route ones:

```python
results = client.search("aurora", types="asset,transcript", limit=50).body()
```

## Things worth knowing

**`replace_*` needs a complete body.** A PUT must carry every replaceable property, and
the server answers 400 naming the ones you left out. Because the models omit unset
fields — matching the server's own mapper, and the Java client's behaviour — a partly
filled model cannot satisfy one. Load the element, change what you need, send it back.

**Asset sub-resources are UUID-only.** `/assets/sha512/{hash}` exists, but nothing
nested under it does. Passing a hash to `tag_asset`, `list_asset_tasks` and the like
raises `ValueError` rather than issuing a request that would 404.

**Timestamps come in two shapes.** Most are ISO-8601 strings; a few (search's
`sort_date` and `last_synced_at`) are epoch seconds. Models keep the raw value;
`parse_instant()` handles both.

**Unknown fields survive.** Anything this version does not know about is kept and
written back out, so load-modify-save against a newer server does not lose data.

## Development

```bash
./setup.sh     # create .venv, install with dev extras
./test.sh      # run the tests
./lint.sh      # ruff check + format check
```

### Integration tests

Skipped unless `LOOM_IT=1`, because they need a running server:

```bash
cd ../..
./start-postgres.sh
./start-demo.sh                 # -> http://localhost:8092
cd clients/python
LOOM_IT=1 ./test.sh
```

`./setup-pool.sh` is not needed — that is for the Java test suite.

### Keeping up with the server

The models are generated from the server's Java model classes, because the published
API description carries no schemas to generate from. After those classes change:

```bash
python3 tools/generate_models.py          # rewrite loom_client/models/
python3 tools/generate_models.py --check  # or just check whether it is stale
python3 tools/extract_fixtures.py         # refresh the round-trip fixtures
```

`tests/test_parity.py` is the safety net: it fails when the Java client gains a method
this one does not have, when this one grows a method with no Java counterpart, and when
a path here is not one the server registers.

## Licence

Apache 2.0. See [LICENSE.txt](LICENSE.txt).
