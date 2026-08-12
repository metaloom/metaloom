# Loom - Demo Container

The image behind `./start-demo.sh`: a Loom server, the built UI, and the media the demo database is
seeded with.

## What this image carries that the server image does not

| Path | What |
| --- | --- |
| `/loom/loom.jar` | the shaded server, main class `LoomDemoRunner` |
| `/loom/ui` | the built `loom-ui` bundle, served at `/ui/` |
| `/demo-content` | the checked-in `demo-content/` directory — sixteen photographs, five clips, ten portraits |

`ENV LOOM_DEMO_CONTENT_DIR=/demo-content` is what makes `DemoDatabaseInitializer` seed real bytes.
The seed itself is **not** demo-only: it runs on every installation, including the server image,
because there is no flag for it. The server image simply has no such directory, so the initializer
paints its images instead and creates the video assets as rows without binaries. Both states are
supported; only this one has photographs in it.

Because the media is copied in rather than mounted, a reader who pulls the image gets the pictures
the documentation shows. `/uploads` — where the seeder writes the content-addressed copies it makes
— is a volume, so the seeded binaries survive a container replacement but not a volume one.

## Running it

The container is **not** self-contained: it needs Postgres.

```bash
./start-postgres.sh && ./start-demo.sh     # http://localhost:8092/ui/  admin / finger
```

Rebuild after changing the server or the UI (`jvm demo`, not bare `demo` — the native variant needs
GraalVM):

```bash
mvn -T 8 clean package -DskipTests -pl loom/containers/demo -am
( cd loom-ui && npm run build )
( cd loom/containers && ./build-containers.sh jvm demo )
```

Recreating the container against an **existing** database leaves the previous run's asset rows in
place while `/uploads` may be empty, and every preview then answers 404. Re-run `./start-postgres.sh`
for a clean re-seed.

See [spec/website/WEBSITE.md](../../../spec/website/WEBSITE.md) § "Capturing Loom UI screenshots" for
the full screenshot workflow this image exists to serve.
