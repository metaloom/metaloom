/**
 * How full a pool is, for the donut on its card.
 *
 * <p>`/pools` reports `freeSpace` and (sometimes) `usedSpace`, never a capacity — the card's
 * `totalSize` is always 0. The card used to fall back to `freeSpace` as the capacity, and then
 * divide `usedSpace` by it: a pool with 100 GB free and 50 GB used was drawn 50% full with a
 * tooltip reading "100 GB free / 100 GB total", when it is 150 GB with a third used. With
 * `usedSpace` larger than `freeSpace` the ring simply read 100%.</p>
 *
 * <p>The capacity is the explicit total when there is one, and otherwise free plus used. Null when
 * there is nothing to draw — no free-space figure, or a capacity of zero.</p>
 */
export interface PoolCapacity {
  total: number;
  used: number;
  free: number;
  /** Fraction used, 0..1. */
  usedFraction: number;
}

export function poolCapacity(freeSpace: number | undefined, usedSpace: number | undefined, totalSize: number): PoolCapacity | null {
  if (freeSpace == null || !(freeSpace >= 0)) return null;
  const knownUsed = usedSpace != null && usedSpace >= 0 ? usedSpace : undefined;
  const total = totalSize > 0 ? totalSize : freeSpace + (knownUsed ?? 0);
  if (!(total > 0)) return null;
  const used = knownUsed ?? Math.max(0, total - freeSpace);
  return { total, used, free: freeSpace, usedFraction: Math.min(1, Math.max(0, used / total)) };
}
