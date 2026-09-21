import React from "react";
import { useTranslation } from "react-i18next";
import { Avatar, Box, Chip, Tooltip, Typography } from "@mui/material";
import { FaceOutlined, GroupWorkOutlined, PersonOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { FaceCrop } from "../faceDetection/FaceCrop";
import { DetectedFace, FaceCluster, Person } from "../../types";
import { formatDuration } from "./helpers";

/**
 * One face tile.
 *
 * <p>Shared by the clustered and the unclustered lists because they were not shared before: only
 * the clustered branch had a click handler, and `cluster.faceIds` is empty for every asset loaded
 * through this screen, so in practice *every* face landed in the branch that did nothing.
 * Enlarging on hover is the other half — judging a 48-pixel square is guesswork.</p>
 *
 * <p>Declared at module scope and memoised, which is load-bearing rather than tidiness. It used
 * to be a function defined inside the panel's body, so React saw a <em>different component type</em>
 * on every render: hovering a tile set state in the asset viewer, the viewer re-rendered, and
 * every tile in the list was unmounted and rebuilt. Each rebuild threw away the crop's object URL
 * and re-fetched it, so the whole strip blinked back to placeholder icons and the tile under the
 * pointer fired a fresh `mouseenter` — the flicker.</p>
 */
const FaceTile = React.memo(function FaceTile({ face, at, onSeek, onHover, confidenceLabel }: {
  face: DetectedFace;
  at: number | null;
  onSeek?: (face: DetectedFace) => void;
  onHover?: (faceId: string | null) => void;
  confidenceLabel: string;
}) {
  const clickable = at != null && !!onSeek;
  return (
    <Tooltip title={`${confidenceLabel}${at != null ? ` · ${formatDuration(Math.round(at))}` : ""}`}>
      <Box
        data-testid="asset-face-tile"
        data-face-id={face.id}
        data-face-time={at ?? ""}
        onClick={clickable ? () => onSeek!(face) : undefined}
        onMouseEnter={() => onHover?.(face.id)}
        onMouseLeave={() => onHover?.(null)}
        sx={{
          width: 48, height: 48, borderRadius: tokens.radius.sm, overflow: "hidden",
          border: `2px solid ${tokens.border.subtle}`, cursor: clickable ? "pointer" : "default",
          "&:hover": clickable ? { borderColor: tokens.primary.main } : {},
          transition: "border-color 120ms ease",
        }}
      >
        {/* FaceCrop, not <img src>: the crop route needs an Authorization header, and
            DetectedFace.thumbnailUrl is hardcoded to "" where these are mapped - so this
            was an <img src=""> and every tile was a broken image. */}
        <FaceCrop assetUuid={face.assetId} detectionUuid={face.id} size={48} rounded={false}
          hoverZoom zoomSize={220} zoomCaption={at != null ? formatDuration(Math.round(at)) : undefined} />
      </Box>
    </Tooltip>
  );
});

export function FaceDetectionPanel({
  faces,
  clusters,
  persons,
  timeOf,
  onSeekToFace,
  onHoverFace,
}: {
  faces: DetectedFace[];
  clusters: FaceCluster[];
  persons: Person[];
  /**
   * Where a face sits in the video, in seconds, or null when it cannot be placed.
   *
   * Supplied rather than read off `face.timestamp`, which holds the detection's frame *number*.
   * This panel used to hand that straight to a seek, so clicking a face two thirds of the way
   * through an episode asked the player for second 62000.
   */
  timeOf: (face: DetectedFace) => number | null;
  onSeekToFace?: (face: DetectedFace) => void;
  /** Lets the timeline light up the tick for the face under the pointer. */
  onHoverFace?: (faceId: string | null) => void;
}) {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
  // Group faces by cluster
  const grouped = clusters.filter(c => c.faceIds.some(fid => faces.some(f => f.id === fid))).map(cluster => {
    const clusterFaces = faces.filter(f => cluster.faceIds.includes(f.id));
    const person = cluster.personId ? persons.find(p => p.id === cluster.personId) : undefined;
    return { cluster, faces: clusterFaces, person };
  });

  const unclustered = faces.filter(f => !f.clusterId || !clusters.some(c => c.id === f.clusterId));

  /**
   * One tile's props. A plain function, deliberately not a component: wrapping `FaceTile` in
   * another component here would reintroduce the identity churn the memo exists to stop.
   */
  const tileProps = (face: DetectedFace) => ({
    face,
    at: timeOf(face),
    onSeek: onSeekToFace,
    onHover: onHoverFace,
    confidenceLabel: tAD("faces.confidence", { pct: (face.confidence * 100).toFixed(0) }),
  });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Summary */}
      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <FaceOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem" }}>{tAD("faces.detected", { count: faces.length })}</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <GroupWorkOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem" }}>{tAD("faces.clusters", { count: grouped.length })}</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <PersonOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem" }}>{tAD("faces.identified", { count: grouped.filter(g => g.person).length })}</Typography>
        </Box>
      </Box>

      {/* Clusters */}
      {grouped.map(({ cluster, faces: cFaces, person }) => (
        <Box key={cluster.id} sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
          {/* Cluster header */}
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 1.5, py: 1, bgcolor: tokens.bg.overlay }}>
            <Avatar src={cluster.representativeThumbnailUrl} sx={{ width: 28, height: 28 }} />
            <Box sx={{ flex: 1 }}>
              <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem", color: tokens.text.primary }}>
                {person ? person.name : cluster.label}
              </Typography>
              {person && (
                <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary, display: "block" }}>
                  {person.description}
                </Typography>
              )}
            </Box>
            {person ? (
              <Chip label={tAD("faces.identifiedChip")} size="small" sx={{ height: 18, fontSize: "0.62rem", bgcolor: `${tokens.accent.green}22`, color: tokens.accent.green }} />
            ) : (
              <Chip label={tAD("faces.unidentifiedChip")} size="small" sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.elevated, color: tokens.text.tertiary }} />
            )}
          </Box>
          {/* Face thumbnails */}
          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.25 }}>
            {cFaces.map(face => <FaceTile key={face.id} {...tileProps(face)} />)}
          </Box>
        </Box>
      ))}

      {/* Unclustered */}
      {unclustered.length > 0 && (
        <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
          <Box sx={{ px: 1.5, py: 1, bgcolor: tokens.bg.overlay }}>
            <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem", color: tokens.text.tertiary }}>{tAD("faces.unclustered", { count: unclustered.length })}</Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.25 }}>
            {unclustered.map(face => <FaceTile key={face.id} {...tileProps(face)} />)}
          </Box>
        </Box>
      )}

      {faces.length === 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
          <FaceOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
          <Typography variant="body2" color="text.secondary">{tAD("empty.noFaces")}</Typography>
        </Box>
      )}
    </Box>
  );
}
