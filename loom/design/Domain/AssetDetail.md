# Asset (Detail)

The `Asset Binary` contains the immutable contents of an asset

This includes:

* sha512
* sha256
* md5
* fingerprint
* face detections
* dominant color

```d2 layout=elk theme=200
direction: right
title: |md
  # Asset Detail
| { near: top-center }


a1: Asset 1 { shape: package }
a2: Asset 2 { shape: package }
a3: Asset 3 { shape: package }
bc: Binary Collection { shape: cylinder }

bc: {
 ab1: Asset Binary 1 { shape: class 
   shape: class

  path: "string"
  dominantColor: "string"
  sha512: "string"
  sha256: "string"
  md5: "string"
  fingerprint: "string"
  size: "long"
 }
 ab2: Asset Binary 2 { shape: package }
}

a1 -> bc.ab1
a2 -> bc.ab2
a3 -> bc.ab2
bc.ab1 ->bc.ab2: linked via fingerprint

```