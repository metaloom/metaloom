import { useEffect, useState } from "react";
import { Avatar, Box } from "@mui/material";
import FaceOutlinedIcon from "@mui/icons-material/FaceOutlined";

import { fetchDetectionCrop } from "../../api/detections";
import { useAuth } from "../../context/AuthContext";

interface FaceCropProps {
  assetUuid?: string;
  detectionUuid?: string;
  size?: number;
  /** Rounded like an avatar, or square like a thumbnail. */
  rounded?: boolean;
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
 * <p>A missing crop is the normal case, not an error: crops only exist once the face-detection node
 * has run over the asset.</p>
 */
export function FaceCrop({ assetUuid, detectionUuid, size = 64, rounded = true }: FaceCropProps) {
  const { token } = useAuth();
  const [url, setUrl] = useState<string | null>(null);

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

  if (!url) {
    return (
      <Box
        sx={{
          width: size,
          height: size,
          borderRadius: rounded ? "50%" : 1,
          bgcolor: "action.hover",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: "text.disabled",
        }}
        aria-label="No face crop available"
      >
        <FaceOutlinedIcon fontSize="small" />
      </Box>
    );
  }

  return (
    <Avatar
      src={url}
      alt=""
      variant={rounded ? "circular" : "rounded"}
      sx={{ width: size, height: size }}
    />
  );
}
