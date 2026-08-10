Demo portraits
==============

Account pictures (USER_AVATAR) and person images (PERSON_IMAGE) seeded by
DemoDatabaseInitializer. Everything else the demo shows is painted at runtime
(see Palette in that class); faces are shipped because a painted gradient does
not read as a person.

Three faces, each in two framings, so a person with two pictures has two
pictures of the same person:

  portrait-teal-wide.jpg    portrait-teal-close.jpg      John Doe
  portrait-frost-wide.jpg   portrait-frost-close.jpg     Alice Smith / editor
  portrait-violet-wide.jpg  portrait-violet-close.jpg    Bob Wilson / admin

Each is a 512x512 square crop -- the size an avatar (48-72px) and a person's
picture gallery need. Keep them that way: this directory ships inside every
Loom jar and container image.

Source: Pexels (https://www.pexels.com), free to use, no attribution required.

  portrait-teal-*    pexels-didsss-29140641.jpg
  portrait-frost-*   pexels-merlin-11167639.jpg
  portrait-violet-*  pexels-merlin-11167645.jpg

The uncropped originals are not checked in. To re-cut a crop, fetch the source
and use the geometry below (crop=W:H:X:Y then scale to 512x512):

  teal-wide     3000:3000:276:500     teal-close     2400:2400:500:900
  frost-wide    1800:1800:322:450     frost-close    1200:1200:622:750
  violet-wide   2400:2400:700:400     violet-close   1600:1600:1100:920
