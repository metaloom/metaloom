Demo portraits (fall-back)
==========================

Account pictures (USER_AVATAR) and person images (PERSON_IMAGE) for installations
that ship no demo media directory.

Where LOOM_DEMO_CONTENT_DIR resolves -- which is the demo container, and a server
started from the source tree -- DemoDatabaseInitializer cuts these framings out of
demo-content/ instead, and these files are not read at all. Everywhere else they
are: the demo seed runs on every installation, and a painted gradient does not
read as a person.

Three faces, each in two framings, so a person with two pictures has two pictures
of the same person:

  portrait-teal-wide.jpg    portrait-teal-close.jpg      John Doe
  portrait-frost-wide.jpg   portrait-frost-close.jpg     Alice Smith / admin
  portrait-violet-wide.jpg  portrait-violet-close.jpg    Bob Wilson / editor

Three faces is also why the fall-back seeds three people rather than the five the
demo media supports: a fourth would have to reuse a face, and two people wearing
one face is exactly what a broken clustering run looks like.

Each is a 512x512 square crop -- the size an avatar (48-72px) and a person's
picture gallery need. Keep them that way: this directory ships inside every
Loom jar and container image.

Source: Pexels (https://www.pexels.com), free to use, no attribution required.
The uncropped originals are checked in under demo-content/users/:

  portrait-teal-*    pexels-didsss-29140641.jpg
  portrait-frost-*   primary-pexels-merlin-11167639.jpg
  portrait-violet-*  pexels-merlin-11167645.jpg

The geometry below is what produced these files, and DemoFace.ADMIN reuses the
frost-wide numbers to cut the same framing out of the original at seed time --
so the demo's own account looks the same in both modes (crop=W:H:X:Y then scale
to 512x512):

  teal-wide     3000:3000:276:500     teal-close     2400:2400:500:900
  frost-wide    1800:1800:322:450     frost-close    1200:1200:622:750
  violet-wide   2400:2400:700:400     violet-close   1600:1600:1100:920
