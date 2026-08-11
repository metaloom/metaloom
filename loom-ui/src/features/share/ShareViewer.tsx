import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Box, Button, Chip, CircularProgress, Divider, Typography } from "@mui/material";
import ArrowBackOutlined from "@mui/icons-material/ArrowBackOutlined";
import DownloadOutlined from "@mui/icons-material/DownloadOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import {
  createSharedAnnotation,
  createSharedComment,
  createSharedReaction,
  deleteSharedAnnotation,
  deleteSharedComment,
  deleteSharedReaction,
  listSharedAnnotations,
  listSharedAssets,
  listSharedComments,
  listSharedReactions,
  sharedBinaryUrl,
  sharedDownloadUrl,
  ShareApiError,
  type ShareAnnotationResponse,
  type ShareCommentResponse,
  type ShareReactionResponse,
  type SharedAssetResponse,
} from "../../api/shares";
import { useShareSession, type ShareSession } from "./ShareSessionContext";
import ShareMedia, { type ShareMediaHandle } from "./ShareMedia";
import ShareFeedbackPanel from "./ShareFeedbackPanel";
import ShareRegionOverlay, { type NormalisedRegion } from "./ShareRegionOverlay";
import { formatFileSize, formatTimecode, mediaKindOf } from "./shareExpiry";

/**
 * What the customer actually sees once they are in.
 *
 * One component for both kinds of link. A collection share opens on the tile grid and drills into
 * a single asset; an asset share starts already drilled in, because the server answers its listing
 * with exactly one element. Keeping that decision on the server means this file has one code path
 * and a link can change kind without a client release.
 */
export default function ShareViewer({ session }: { session: ShareSession }) {
  const { t } = useTranslation();
  const { clearSession } = useShareSession();
  const { slug, token } = session;

  const [assets, setAssets] = useState<SharedAssetResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<SharedAssetResponse | null>(null);

  const [comments, setComments] = useState<ShareCommentResponse[]>([]);
  const [annotations, setAnnotations] = useState<ShareAnnotationResponse[]>([]);
  const [reactions, setReactions] = useState<ShareReactionResponse[]>([]);
  const [currentTime, setCurrentTime] = useState(0);
  /** Armed by the feedback panel: the next drag across the media draws a box instead of scrubbing. */
  const [drawingRegion, setDrawingRegion] = useState(false);

  const mediaRef = useRef<ShareMediaHandle>(null);

  const isSingleAsset = session.targetType === "ASSET";

  /**
   * A lapsed session must put the gate back rather than showing an error the visitor cannot act
   * on. The token outlives neither the link nor its own expiry, and both surface here as a 401.
   */
  const handleError = useCallback(
    (e: unknown) => {
      if (e instanceof ShareApiError && e.status === 401) {
        clearSession();
        return;
      }
      if (e instanceof ShareApiError && e.status === 404) {
        setError(t("share.viewer.gone"));
        return;
      }
      setError(t("share.viewer.failed"));
    },
    [clearSession, t],
  );

  useEffect(() => {
    let cancelled = false;
    listSharedAssets(slug, token, { limit: 100 })
      .then((response) => {
        if (cancelled) return;
        const list = response.data ?? [];
        setAssets(list);
        // An asset share has exactly one element; opening straight into it saves a click that only
        // ever has one possible outcome.
        if (isSingleAsset && list.length === 1) setSelected(list[0]);
      })
      .catch((e) => !cancelled && handleError(e));
    return () => {
      cancelled = true;
    };
  }, [slug, token, isSingleAsset, handleError]);

  const refreshFeedback = useCallback(async () => {
    try {
      const [commentList, annotationList, reactionList] = await Promise.all([
        session.allowComments ? listSharedComments(slug, token) : Promise.resolve({ data: [] as ShareCommentResponse[] }),
        session.allowAnnotations
          ? listSharedAnnotations(slug, token)
          : Promise.resolve({ data: [] as ShareAnnotationResponse[] }),
        session.allowReactions ? listSharedReactions(slug, token) : Promise.resolve({ data: [] as ShareReactionResponse[] }),
      ]);
      setComments(commentList.data ?? []);
      setAnnotations(annotationList.data ?? []);
      setReactions(reactionList.data ?? []);
    } catch (e) {
      handleError(e);
    }
  }, [slug, token, session.allowComments, session.allowAnnotations, session.allowReactions, handleError]);

  useEffect(() => {
    if (session.allowComments || session.allowAnnotations || session.allowReactions) {
      void refreshFeedback();
    }
  }, [refreshFeedback, session.allowComments, session.allowAnnotations, session.allowReactions]);

  const feedbackForSelection = useMemo(() => {
    if (!selected) return { comments, annotations, reactions };
    // On a collection share the panel follows the open asset, so a note left on clip two does not
    // appear under clip three.
    return {
      comments: comments.filter((c) => !c.assetUuid || c.assetUuid === selected.uuid),
      annotations: annotations.filter((a) => a.assetUuid === selected.uuid),
      reactions: reactions.filter((r) => !r.assetUuid || r.assetUuid === selected.uuid),
    };
  }, [selected, comments, annotations, reactions]);

  const addComment = async (text: string, parentUuid?: string) => {
    try {
      await createSharedComment(slug, token, { text, parentUuid, assetUuid: selected?.uuid });
      await refreshFeedback();
    } catch (e) {
      handleError(e);
    }
  };

  const removeComment = async (uuid: string) => {
    try {
      await deleteSharedComment(slug, token, uuid);
      await refreshFeedback();
    } catch (e) {
      handleError(e);
    }
  };

  const toggleReaction = async (type: string) => {
    try {
      const existing = reactions.find((r) => r.type === type && (!selected || r.assetUuid === selected.uuid));
      if (existing) {
        await deleteSharedReaction(slug, token, existing.uuid);
      } else {
        await createSharedReaction(slug, token, { type, assetUuid: selected?.uuid });
      }
      await refreshFeedback();
    } catch (e) {
      handleError(e);
    }
  };

  const addTimecodeMark = async (text: string) => {
    if (!selected) return;
    try {
      // Anchored at the playhead, so the customer never types a timecode. Still-media has no
      // playhead, which is why an image mark is written at 0 rather than refused.
      const at = mediaRef.current?.currentTime() ?? 0;
      await createSharedAnnotation(slug, token, {
        assetUuid: selected.uuid,
        kind: "TEMPORAL",
        timeFrom: Number.isFinite(at) ? at : 0,
        text,
      });
      await refreshFeedback();
    } catch (e) {
      handleError(e);
    }
  };

  /**
   * A drawn box becomes a mark anchored at the playhead as well as on the frame.
   *
   * SPATIOTEMPORAL rather than SPATIAL whenever the media has a position, because "this logo" is
   * almost never a note about the whole clip - it is a note about the logo at the moment it is
   * wrong. Still media has no playhead, and gets a plain SPATIAL mark.
   */
  const addRegionMark = async (region: NormalisedRegion) => {
    if (!selected) return;
    setDrawingRegion(false);
    try {
      const at = mediaRef.current?.currentTime();
      const temporal = at !== undefined && Number.isFinite(at);
      await createSharedAnnotation(slug, token, {
        assetUuid: selected.uuid,
        kind: temporal ? "SPATIOTEMPORAL" : "SPATIAL",
        timeFrom: temporal ? at : undefined,
        ...region,
      });
      await refreshFeedback();
    } catch (e) {
      handleError(e);
    }
  };

  const removeAnnotation = async (uuid: string) => {
    try {
      await deleteSharedAnnotation(slug, token, uuid);
      await refreshFeedback();
    } catch (e) {
      handleError(e);
    }
  };

  const showFeedback = session.allowComments || session.allowReactions || session.allowAnnotations;

  return (
    <Box data-testid="share-viewer" sx={{ minHeight: "100vh", bgcolor: tokens.bg.base, color: tokens.text.primary }}>
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          px: { xs: 2, md: 4 },
          py: 2,
          borderBottom: `1px solid ${tokens.border.subtle}`,
        }}
      >
        {selected && !isSingleAsset && (
          <Button
            size="small"
            startIcon={<ArrowBackOutlined />}
            onClick={() => setSelected(null)}
            data-testid="share-back"
            sx={{ color: tokens.text.secondary }}
          >
            {t("share.viewer.back")}
          </Button>
        )}
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography sx={{ fontSize: "1.05rem", fontWeight: 700 }} data-testid="share-title">
            {selected ? selected.title || selected.filename : session.targetName}
          </Typography>
          {session.targetDescription && !selected && (
            <Typography variant="body2" sx={{ color: tokens.text.secondary }}>
              {session.targetDescription}
            </Typography>
          )}
        </Box>
        <Chip size="small" label={session.visitorName} data-testid="share-visitor" sx={{ bgcolor: tokens.bg.surface }} />
      </Box>

      {error && (
        <Alert severity="error" data-testid="share-viewer-error" sx={{ m: { xs: 2, md: 4 } }}>
          {error}
        </Alert>
      )}

      <Box sx={{ px: { xs: 2, md: 4 }, py: 3 }}>
        {assets === null && !error && (
          <Box sx={{ display: "flex", justifyContent: "center", py: 8 }}>
            <CircularProgress size={28} />
          </Box>
        )}

        {assets !== null && assets.length === 0 && (
          <Typography data-testid="share-empty" sx={{ color: tokens.text.secondary, textAlign: "center", py: 8 }}>
            {t("share.viewer.empty")}
          </Typography>
        )}

        {assets !== null && assets.length > 0 && !selected && (
          <Box
            data-testid="share-grid"
            sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: 2 }}
          >
            {assets.map((asset) => (
              <ShareTile key={asset.uuid} slug={slug} asset={asset} onOpen={() => setSelected(asset)} />
            ))}
          </Box>
        )}

        {selected && (
          <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: showFeedback ? "1fr 360px" : "1fr" }, gap: 3 }}>
            <Box>
              <ShareRegionOverlay
                annotations={feedbackForSelection.annotations}
                drawing={drawingRegion}
                onRegionDrawn={addRegionMark}
                onSeek={(seconds) => mediaRef.current?.seekTo(seconds)}
              >
                <ShareMedia
                  ref={mediaRef}
                  slug={slug}
                  asset={selected}
                  onTimeUpdate={setCurrentTime}
                />
              </ShareRegionOverlay>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2, mt: 2, flexWrap: "wrap" }}>
                {session.showMetadata && <AssetFacts asset={selected} />}
                <Box sx={{ flex: 1 }} />
                {session.allowDownload && (
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={<DownloadOutlined />}
                    href={sharedDownloadUrl(slug, selected.uuid)}
                    data-testid="share-download"
                  >
                    {t("share.viewer.download")}
                  </Button>
                )}
              </Box>
              {session.showMetadata && selected.description && (
                <>
                  <Divider sx={{ my: 2, borderColor: tokens.border.subtle }} />
                  <Typography variant="body2" sx={{ color: tokens.text.secondary }}>
                    {selected.description}
                  </Typography>
                </>
              )}
            </Box>

            {showFeedback && (
              <Box
                sx={{
                  bgcolor: tokens.bg.panel,
                  border: `1px solid ${tokens.border.subtle}`,
                  borderRadius: tokens.radius.lg,
                  p: 2,
                  alignSelf: "flex-start",
                }}
              >
                <ShareFeedbackPanel
                  comments={feedbackForSelection.comments}
                  annotations={feedbackForSelection.annotations}
                  reactions={feedbackForSelection.reactions}
                  allowComments={session.allowComments}
                  allowReactions={session.allowReactions}
                  allowAnnotations={session.allowAnnotations}
                  currentTime={currentTime}
                  onAddComment={addComment}
                  onDeleteComment={removeComment}
                  onToggleReaction={toggleReaction}
                  onAddTimecodeMark={addTimecodeMark}
                  onDeleteAnnotation={removeAnnotation}
                  onSeek={(seconds) => mediaRef.current?.seekTo(seconds)}
                  drawingRegion={drawingRegion}
                  onToggleDrawRegion={() => setDrawingRegion((armed) => !armed)}
                />
              </Box>
            )}
          </Box>
        )}
      </Box>
    </Box>
  );
}

function AssetFacts({ asset }: { asset: SharedAssetResponse }) {
  const facts = [
    asset.duration !== undefined ? formatTimecode(asset.duration) : null,
    asset.width && asset.height ? `${asset.width}×${asset.height}` : null,
    formatFileSize(asset.size) || null,
  ].filter(Boolean);
  if (facts.length === 0) return null;
  return (
    <Typography variant="caption" data-testid="share-asset-facts" sx={{ color: tokens.text.tertiary }}>
      {facts.join(" · ")}
    </Typography>
  );
}

/**
 * One tile in the collection grid.
 *
 * The thumbnail is the stored image itself for images, and a labelled placeholder otherwise — the
 * same rule the internal asset grid follows, for the same reason: there is no thumbnail service, so
 * a poster frame for a video does not exist to show.
 */
function ShareTile({ slug, asset, onOpen }: { slug: string; asset: SharedAssetResponse; onOpen: () => void }) {
  const [failed, setFailed] = useState(false);
  const isImage = mediaKindOf(asset.mimeType) === "image";
  return (
    <Box
      data-testid="share-tile"
      onClick={onOpen}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") onOpen();
      }}
      sx={{
        cursor: "pointer",
        borderRadius: tokens.radius.md,
        overflow: "hidden",
        border: `1px solid ${tokens.border.subtle}`,
        bgcolor: tokens.bg.panel,
        transition: "border-color 120ms",
        "&:hover": { borderColor: tokens.primary.main },
      }}
    >
      <Box sx={{ position: "relative", pt: "56.25%", bgcolor: "#000" }}>
        {isImage && !failed ? (
          <Box
            component="img"
            src={sharedBinaryUrl(slug, asset.uuid)}
            alt={asset.title || asset.filename}
            loading="lazy"
            onError={() => setFailed(true)}
            sx={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }}
          />
        ) : (
          <Box
            sx={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: tokens.text.tertiary,
              fontSize: "0.75rem",
              textTransform: "uppercase",
              letterSpacing: "0.08em",
            }}
          >
            {asset.mimeType?.split("/")[0] ?? "file"}
          </Box>
        )}
      </Box>
      <Box sx={{ p: 1.25 }}>
        <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
          {asset.title || asset.filename}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
          {asset.duration !== undefined ? formatTimecode(asset.duration) : formatFileSize(asset.size)}
        </Typography>
      </Box>
    </Box>
  );
}
