import { useEffect, useState } from "react";

import { fetchAssetBinaryBlob } from "../api/binaries";
import { useAuth } from "../context/AuthContext";

/**
 * An asset's stored bytes as something an `<img>` can actually load.
 *
 * <p>`GET /assets/:uuid/binary/data` requires an `Authorization` header and an `<img src>` cannot
 * send one, so pointing a picture at that URL is a 401 and a broken-image glyph — which is what
 * the asset viewer showed for every image asset. Video sidesteps this with the `?mt=` media token
 * on the poster and stream routes, but that credential is deliberately mounted on those two
 * routes only: widening it to the original bytes would turn a narrow, short-lived grant into a
 * general download link.</p>
 *
 * <p>So the bytes are fetched with the header and wrapped in a blob URL, exactly as
 * {@link ../features/faceDetection/FaceCrop} does for detection crops. Keeping it in one hook is
 * what keeps the revoke from being forgotten: an object URL that is never released pins the whole
 * decoded image in memory for the life of the tab.</p>
 *
 * <p>Returns `null` while loading and on failure. A caller should treat that as "no picture yet"
 * rather than as an error — a missing binary is an ordinary state for a catalogue that indexes
 * files by reference.</p>
 *
 * @param assetUuid the asset whose bytes to load, or null/undefined to load nothing
 */
export function useAuthedImage(assetUuid?: string | null): string | null {
  const { token } = useAuth();
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!token || !assetUuid) {
      setUrl(null);
      return;
    }
    let objectUrl: string | null = null;
    let cancelled = false;

    fetchAssetBinaryBlob(token, assetUuid)
      .then(blob => {
        if (cancelled || !blob) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        // Degrades to the type placeholder. Nothing here is worth an error banner over.
        if (!cancelled) setUrl(null);
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [token, assetUuid]);

  return url;
}
