
```d2 layout=elk theme=200
direction: right
title: |md
  # Usecase #1
| { near: top-center }


# Project
prc: Project Collection { shape: cylinder

}

```

| Asset  | Locations  |
|--------|-----------|
| #1     | (key1+assetUuid+library),(key2),(key3)  |
| #2     | (key1+assetUuid+library),(key2),(key3)  |

`select * from asset_location where assetUuid="#1" and libraryUuid="#1"`
