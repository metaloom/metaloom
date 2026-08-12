# Demo content

The media the demo container seeds its database with: account and person portraits, image and video
material for the face and object detection pipelines, and a set of artistic photographs for browsing,
thumbnailing and general UI demos.

`DemoDatabaseInitializer` (`loom/core/src/main/java/io/metaloom/loom/core/boot/`) reads this
directory at first boot. The demo `Containerfile` copies it to `/demo-content` and points
`LOOM_DEMO_CONTENT_DIR` at it. When the directory is absent — which is the case for the plain server
image, since the seed runs there too — the initializer falls back to the images it paints itself and
to the six portraits shipped in `loom/core/src/main/resources/demo/portraits/`.

Machine-readable attribution for every file lives in [CREDITS.json](CREDITS.json).

```
demo-content/
├── persons/    5 person portraits  (640x640, face-centred crop)
├── users/      5 account portraits (2 crops + 3 uncropped originals)
├── images/    16 photographs        (6 scene, max 1920px · 10 artistic, max 2560px)
└── videos/     2 clips + 4 derived  (1920x1080, H.264, no audio track)
```

The initializer resizes every seeded image to 1600 px on the long edge. There is no thumbnail
service in Loom — a preview *is* the stored binary — so a grid of sixteen 2560 px originals would be
a ~16 MB first screen. Keep that in mind before adding a picture here: what ships is the source, not
what the demo serves.

## Portraits

Frontal, evenly lit, one clearly visible face per image — usable as reference faces for recognition,
not just as UI decoration. The set spans a range of ages, skin tones and genders so that recognition
and clustering demos are not trivially separable.

### `persons/` — the demo's person records

| File | Description | Credit |
| --- | --- | --- |
| `person-01-woman-long-hair.jpg` | Young woman with long brown hair wearing a black shirt | [Beatriz Cattel](https://unsplash.com/@bicattel) ([source](https://unsplash.com/photos/young-woman-with-long-brown-hair-wearing-a-black-shirt-UZmSn5GaSWY)) |
| `person-02-man-blue-shirt.jpg` | Man in blue crew neck shirt | [Ludovic Migneault](https://unsplash.com/@dargonesti) ([source](https://unsplash.com/photos/man-in-blue-crew-neck-shirt-4uj3iZ5m084)) |
| `person-04-man-beard.jpg` | A smiling man wearing a black sweatshirt. | [Jadon Johnson](https://unsplash.com/@jadonjohnson) ([source](https://unsplash.com/photos/a-smiling-black-man-wearing-a-black-sweatshirt-aPnIGxGUV68)) |
| `person-08-older-woman.jpg` | Smiling woman with grey hair | [Ravi Patel](https://unsplash.com/@ravi_patel) ([source](https://unsplash.com/photos/smiling-woman-VMGAbeeJTKo)) |
| `user-02-woman-glasses.jpg` | A woman with glasses smiles against a gray background. | [Vitaly Gariev](https://unsplash.com/@silverkblack) ([source](https://unsplash.com/photos/a-woman-with-glasses-smiles-against-a-gray-background-s3aDHKxHZSc)) |

**Note:** these are stock portraits, so the `persons/` faces do **not** appear in any of the images
or videos below. They are fine as person records and as recognition input on their own, but a demo
that expects a person avatar to match a face found in the demo footage will not produce a hit — which
is why the demo seed leaves the faces it detects in `image-02`/`image-03` in an unreviewed cluster
rather than assigning them to these people.

### `users/` — the demo accounts

| File | Used for | Description | Credit |
| --- | --- | --- | --- |
| `primary-pexels-merlin-11167639.jpg` | **`admin`** — the primary account picture | Portrait, uncropped original | [Pexels](https://www.pexels.com) |
| `user-03-man-black-shirt.jpg` | `editor` | A happy man smiling in a black shirt. | [melvin Ankrah](https://unsplash.com/@hashtagmelvin) ([source](https://unsplash.com/photos/a-happy-black-man-smiling-in-a-black-shirt-fEY925P2GlQ)) |
| `pexels-didsss-29140641.jpg` | fallback portrait source (`portrait-teal-*`) | Portrait, uncropped original | [Pexels](https://www.pexels.com) |
| `pexels-merlin-11167645.jpg` | fallback portrait source (`portrait-violet-*`) | Portrait, uncropped original | [Pexels](https://www.pexels.com) |
| `user-05-man-mustache.jpg` | spare | A man looks directly at the camera. | [Vishnu K R](https://unsplash.com/@wish_species) ([source](https://unsplash.com/photos/a-man-looks-directly-at-the-camera-h7ZxqNdnNXU)) |

The `viewer` account deliberately has **no** picture: the storage and user screens are more useful
when one account shows the initials fallback.

The three `pexels-*` files are the uncropped originals the six 512x512 crops in
`loom/core/src/main/resources/demo/portraits/` were cut from; that directory's `README.txt` records
the crop geometry. They live here rather than in `loom-test-env/` because this is now the one place
demo media is kept.

## Scene images

Chosen so that both pipelines have something to find: 4 of 6 contain clearly visible faces, and
between them they cover a broad set of COCO classes — person, car, bus, bicycle, traffic light,
laptop, bottle, book, cell phone, backpack, handbag, dog, chair, dining table, bowl.

| File | Description | Credit |
| --- | --- | --- |
| `image-01-people-crossing-street.jpg` | People crossing a street at a crosswalk with traffic lights. | [Oliver Streit](https://unsplash.com/@streit0liver) ([source](https://unsplash.com/photos/people-crossing-a-street-at-a-crosswalk-with-traffic-lights-be9q5pX422o)) |
| `image-02-coworkers-laptop-table.jpg` | Four coworkers smiling around laptop at table | [Jud Mackrill](https://unsplash.com/@judmackrill) ([source](https://unsplash.com/photos/four-coworkers-smiling-around-laptop-at-table-Of_m3hMsoAA)) |
| `image-03-three-friends-outdoors.jpg` | Three smiling friends stand together outdoors. | [Apartment Life](https://unsplash.com/@apartmentlife) ([source](https://unsplash.com/photos/three-smiling-friends-stand-together-outdoors-UCuhN-D4-I8)) |
| `image-04-man-riding-bicycle.jpg` | Man in black t-shirt and black cap riding on black city bicycle | [Arthur Edelmans](https://unsplash.com/@arthur_edelmans) ([source](https://unsplash.com/photos/man-in-black-t-shirt-and-black-cap-riding-on-black-city-bicycle-IYiVvQbkUgo)) |
| `image-05-woman-walking-dog.jpg` | Woman in a black leather jacket walking a black and brown short coated dog | [Honest Paws](https://unsplash.com/@honestpaws) ([source](https://unsplash.com/photos/woman-in-black-leather-jacket-and-blue-denim-jeans-holding-black-and-brown-short-coated-dog-J2c_lqMX1AM)) |
| `image-06-street-food-vendor.jpg` | Woman handing money to a street food vendor | [Frankie Shutterbug](https://unsplash.com/@frankieshutterbug) ([source](https://unsplash.com/photos/woman-handing-money-to-person-r284bGzyDm4)) |

`image-02` and `image-03` are the two the demo seeds face detections on — four faces and three, with
boxes measured against the actual pictures, so the face crops the product cuts out of them are real
faces. `image-01` carries the object boxes for the same reason.

## Artistic images

Higher-resolution photographs kept deliberately varied in genre, palette and orientation —
architecture, minimal abstract, seascape, forest, long exposure and alpine landscape. Useful for
thumbnail and preview rendering, colour/palette extraction, aesthetic scoring, EXIF handling and
anything where the detection pipelines are not the point. Most contain no people at all, which
makes them a good negative control for the face and person detectors.

| File | Description | Credit |
| --- | --- | --- |
| `artistic-01-curved-architecture.jpg` | Minimalist photography of a brown wavy structure | [Ricardo Gomez Angel](https://unsplash.com/@rgaleriacom) ([source](https://unsplash.com/photos/minimalist-photography-of-brown-wavy-structure-PzYiCWOHtfU)) |
| `artistic-02-abstract-facade.jpg` | An abstract photo of a curved building with a blue sky in the background | [Tim Stief](https://unsplash.com/@timstief) ([source](https://unsplash.com/photos/an-abstract-photo-of-a-curved-building-with-a-blue-sky-in-the-background-dH6IjhWHNQQ)) |
| `artistic-03-sand-dune-abstract.jpg` | An abstract photo of a wave in the sand | [Dan Meyers](https://unsplash.com/@dmey503) ([source](https://unsplash.com/photos/an-abstract-photo-of-a-wave-in-the-sand-ucmEHogvn1g)) |
| `artistic-04-sea-stack-black-beach.jpg` | Dramatic sea stack on a black sand beach under a cloudy sky. | [Joseph Corl](https://unsplash.com/@jcorl) ([source](https://unsplash.com/photos/dramatic-sea-stack-on-black-sand-beach-under-cloudy-sky-T6-L9tNGgCY)) |
| `artistic-05-misty-forest-path.jpg` | A winding forest path with sunlight filtering through misty trees. | [Spruce](https://unsplash.com/@sprucejpg) ([source](https://unsplash.com/photos/a-winding-forest-path-with-sunlight-filtering-through-misty-trees-TrrYms0ap7E)) |
| `artistic-06-waterfall-long-exposure.jpg` | A small waterfall in the middle of a mountain | [Angelo Casto](https://unsplash.com/@jddartphotographer) ([source](https://unsplash.com/photos/a-small-waterfall-in-the-middle-of-a-mountain-o0L4hKPjh8Q)) |
| `artistic-07-alpine-lake-autumn.jpg` | A mountain range reflected in the still water of a lake | [Tobias Reich](https://unsplash.com/@electerious) ([source](https://unsplash.com/photos/a-mountain-range-is-reflected-in-the-still-water-of-a-lake-kOFvKxzph30)) |
| `artistic-08-glowing-autumn-forest.jpg` | Forest during golden hour | [Johannes Plenio](https://unsplash.com/@jplenio) ([source](https://unsplash.com/photos/forest-during-golden-hour-time-sPt5RIjKfpk)) |
| `artistic-09-mountain-lake-reflection.jpg` | Snow covered mountain near a body of water during daytime | [Chris Stenger](https://unsplash.com/@chrisstenger) ([source](https://unsplash.com/photos/snow-covered-mountain-near-body-of-water-during-daytime-fvJwchRL6xw)) |
| `artistic-10-autumn-forest-path.jpg` | Brown and orange trees along a forest path | [Johannes Plenio](https://unsplash.com/@jplenio) ([source](https://unsplash.com/photos/brown-and-orange-trees-EOIToTneyZ4)) |

## Videos

| File | Length | Description | Credit |
| --- | --- | --- | --- |
| `video-01-work-meeting-around-table.mp4` | 28.3s | Four people at a meeting table — the face-detection sample: four faces held on screen for the whole clip, in frontal and profile poses, plus laptops, chairs, glasses and books. | Mixkit ([source](https://mixkit.co/free-stock-video/people-having-a-work-meeting-around-a-table-4547/)) |
| `video-02-busy-street-traffic.mp4` | 13.4s | Busy city intersection — the object-detection sample: dozens of pedestrians plus taxis, cars, a bus, a truck, a cyclist and traffic lights. Shot from above, so faces are too small to detect. | Mixkit ([source](https://mixkit.co/free-stock-video/busy-street-in-the-city-4000/)) |

Both were re-encoded from the ~23 Mbps originals (H.264 CRF 23/24, `+faststart`) to keep the
directory small — 17 MB instead of 155 MB. The source clips have no audio track, so none of the
audio/transcription pipelines can be exercised with this material.

### Derived files

Four files cut from the two clips above with `ffmpeg`, so the remix and deduplication demos are
about real media rather than database rows. Regenerate with:

```bash
cd demo-content/videos
# the remix's derived cut, and a still pulled out of that cut
ffmpeg -y -ss 6 -t 10 -i video-01-work-meeting-around-table.mp4 \
       -c:v libx264 -crf 23 -preset medium -movflags +faststart -an \
       video-01-work-meeting-around-table-cut.mp4
ffmpeg -y -ss 3 -i video-01-work-meeting-around-table-cut.mp4 -frames:v 1 -q:v 3 \
       video-01-work-meeting-around-table-still.jpg
# poster frame, used by the mocked website capture scripts
ffmpeg -y -ss 1 -i video-01-work-meeting-around-table.mp4 -frames:v 1 -vf scale=1280:-2 -q:v 4 \
       video-01-work-meeting-around-table-poster.jpg
# the deduplication demo's near-duplicate: same footage, smaller file
ffmpeg -y -i video-02-busy-street-traffic.mp4 -vf scale=1280:720 \
       -c:v libx264 -crf 28 -preset medium -movflags +faststart -an \
       video-02-busy-street-traffic-720p.mp4
```

| File | Role in the demo |
| --- | --- |
| `video-01-work-meeting-around-table-cut.mp4` | the remix's `DERIVED` member — a 10 s cut of the source |
| `video-01-work-meeting-around-table-still.jpg` | the remix's second `DERIVED` member — one frame of that cut |
| `video-01-work-meeting-around-table-poster.jpg` | poster frame for the mocked share/remix capture scripts |
| `video-02-busy-street-traffic-720p.mp4` | the deduplication group's duplicate — a lower-bitrate re-encode, so the machine's KEEP choice (the largest complete candidate) is the obvious one |

## Licensing

- **Unsplash images** — [Unsplash License](https://unsplash.com/license): free for commercial and
  non-commercial use, no permission or attribution required (attribution is recorded here anyway).
  Selling unmodified copies or rebuilding a competing photo service is not permitted.
- **Pexels portraits** (`users/*pexels-*`) — [Pexels License](https://www.pexels.com/license/): free
  to use, no attribution required.
- **Videos** — [Mixkit Free Stock Video License](https://mixkit.co/license/#videoFree): free for
  commercial and non-commercial projects, no attribution required. Note that Mixkit does not allow
  redistributing the clips **on a standalone basis** — that is aimed at re-publishing them as stock
  media, but it is worth being aware of before this directory is mirrored anywhere public.

All photographs are standard Unsplash or Pexels licence — no Unsplash+ / premium material is
included, which matters because Unsplash+ carries different redistribution terms. Unsplash has no
video library, which is why the two clips come from Mixkit rather than the same source as the images.
