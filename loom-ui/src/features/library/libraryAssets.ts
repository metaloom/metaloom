import { AssetResponse } from "../../api/assets";

/** True when the asset has a storage location in the given library. */
export function assetInLibrary(asset: AssetResponse, libraryId: string): boolean {
  return (asset.locations ?? []).some(l => l.libraryUuid === libraryId);
}

/** Assets that have at least one location in the given library. */
export function assetsInLibrary(assets: AssetResponse[], libraryId: string): AssetResponse[] {
  return assets.filter(a => assetInLibrary(a, libraryId));
}
