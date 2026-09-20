import { useEffect, useRef, useState } from "react";
import { Avatar, Box, Fade, Paper, Popper, Typography } from "@mui/material";
import FaceOutlinedIcon from "@mui/icons-material/FaceOutlined";

import { fetchDetectionCrop } from "../../api/detections";
import { useAuth } from "../../context/AuthContext";
import { tokens } from "../../theme";

interface FaceCropProps {
  assetUuid?: string;
  detectionUuid?: string;
  size?: number;
  /** Rounded like an avatar, or square like a thumbnail. */
  rounded?: boolean;
  /**
   * Show an enlarged copy in a floating panel while the pointer is over the thumbnail.
   *
   * Off by default. It is a reading aid for the cluster grid, where the question is "is this the
   * same person?" and a 44-pixel square cannot answer it; on a card that is already large it would
   * just be a panel in the way.
   */
  hoverZoom?: boolean;
  /** Longest edge of the enlarged copy. */
  zoomSize?: number;
  /** A line of context under the enlarged copy — which asset, what time. */
  zoomCaption?: string;
  onClick?: () => void;
  onHoverChange?: (hovering: boolean) => void;
}

/**
 * The cropped face for one detection, served from this deployment.
 *
 * <p>Two things this component exists to get right:</p>
 *
 * <ul>
 *   <li><b>The bytes stay in-house.</b> Face crops are biometric data. This used to be an
 *   <code>&lt;img src="https://i.pravatar.cc/80?u={detectionUuid}"&gt;</code>, which sent every
 *   detection uuid to a third party and rendered a stock portrait of somebody who was not in the
 *   picture — a privacy defect wearing the costume of a placeholder.</li>
 *   <li><b>The object URL is revoked.</b> The crop needs an Authorization header, so it cannot be a
 *   plain <code>src</code>; it is fetched and wrapped in a blob URL. Wrapping that in one component
 *   is what keeps the revoke from being forgotten at each of the call sites.</li>
 * </ul>
 *
 * <p>The zoom is part of the same component rather than a wrapper around it for a third reason:
 * a wrapper would have to fetch the crop again to show it larger, so a grid of two hundred faces
 * would hold two hundred duplicate blobs. Here the enlarged copy is the <em>same</em> object URL
 * at a different CSS size — no second request, no second thing to revoke.</p>
 *
 * <p>A missing crop is the normal case, not an error: crops only exist once the face-detection node
 * has run over the asset.</p>
 */
export function FaceCrop({
  assetUuid, detectionUuid, size = 64, rounded = true,
  hoverZoom = false, zoomSize = 320, zoomCaption, onClick, onHoverChange,
}: FaceCropProps) {
  const { token } = useAuth();
  const [url, setUrl] = useState<string | null>(null);
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const hostRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!token || !assetUuid || !detectionUuid) {
      return;
    }
    let objectUrl: string | null = null;
    let cancelled = false;

    fetchDetectionCrop(token, assetUuid, detectionUuid)
      .then(blob => {
        if (cancelled || !blob) {
          return;
        }
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        // A crop that cannot be loaded degrades to the placeholder. Nothing here is worth an error
        // banner over — the reviewer can still act on the cluster without seeing every face.
      });

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [token, assetUuid, detectionUuid]);

  const enter = () => {
    onHoverChange?.(true);
    // No anchor without a picture: a popup of the placeholder icon says nothing and covers the
    // neighbouring faces while it says it.
    if (hoverZoom && url) {
      setAnchorEl(hostRef.current);
    }
  };
  const leave = () => {
    onHoverChange?.(false);
    setAnchorEl(null);
  };

  const hostSx = {
    width: size,
    height: size,
    flex: "0 0 auto",
    cursor: onClick ? "pointer" : undefined,
  } as const;

  const body = !url ? (
    <Box
      sx={{
        ...hostSx,
        borderRadius: rounded ? "50%" : 1,
        bgcolor: "action.hover",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        color: "text.disabled",
      }}
      aria-label="No face crop available"
      data-testid="face-crop"
    >
      <FaceOutlinedIcon fontSize="small" />
    </Box>
  ) : (
    <Avatar
      src={url}
      alt=""
      variant={rounded ? "circular" : "rounded"}
      sx={hostSx}
      data-testid="face-crop"
    />
  );

  return (
    <Box
      ref={hostRef}
      component="span"
      sx={{ display: "inline-flex", lineHeight: 0 }}
      onMouseEnter={enter}
      onMouseLeave={leave}
      onClick={onClick}
    >
      {body}
      {hoverZoom && (
        <Popper
          open={!!anchorEl}
          anchorEl={anchorEl}
          placement="right-start"
          transition
          // Follows the thumbnail past the edge of the viewport instead of being clipped by the
          // scrolling grid, which is why this is a Popper and not an absolutely positioned div.
          modifiers={[{ name: "offset", options: { offset: [0, 12] } },
            { name: "preventOverflow", options: { padding: 8 } }]}
          sx={{ zIndex: 1400, pointerEvents: "none" }}
        >
          {({ TransitionProps }) => (
            <Fade {...TransitionProps} timeout={90}>
              <Paper elevation={8} data-testid="face-crop-zoom"
                sx={{ p: 0.5, bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.default}`, borderRadius: tokens.radius.md }}>
                <Box component="img" src={url ?? undefined} alt=""
                  sx={{ display: "block", width: zoomSize, height: zoomSize, objectFit: "contain", borderRadius: tokens.radius.sm, bgcolor: "#000" }} />
                {zoomCaption && (
                  <Typography variant="caption" sx={{ display: "block", px: 0.5, pt: 0.5, color: tokens.text.tertiary, fontSize: "0.68rem", maxWidth: zoomSize }}>
                    {zoomCaption}
                  </Typography>
                )}
              </Paper>
            </Fade>
          )}
        </Popper>
      )}
    </Box>
  );
}
