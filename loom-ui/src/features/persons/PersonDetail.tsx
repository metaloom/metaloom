import React, { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Avatar, Box, Button, Chip, CircularProgress, IconButton, Paper, Tooltip, Typography,
} from "@mui/material";
import {
  ArrowBackOutlined, CheckCircle, CheckCircleOutlined, DeleteOutlined,
  PhotoLibraryOutlined, UploadOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import EmptyState from "../../components/EmptyState";
import { FaceCrop } from "../faceDetection/FaceCrop";
import { listClusterMembers, type ClusterMemberModel } from "../../api/clusters";
import {
  deletePersonImage, importPersonImage, listPersonClusters, listPersonImages, loadPerson,
  setPersonAvatar, uploadPersonImage,
  type PersonImageResponse, type PersonResponse,
} from "../../api/persons";

/** A face crop offered as a source for one of this person's pictures. */
interface CropCandidate {
  detectionUuid: string;
  assetUuid: string;
}

/**
 * One person: their pictures, and which of them is the avatar.
 *
 * The pictures belong to the person rather than to any asset, so they outlive the material
 * somebody was found in. Two ways in: upload a file, or take a copy of a face crop from a
 * cluster already confirmed to this person — the latter being the one-click path from
 * "discovered in a video" to an avatar that is actually a picture of their face.
 *
 * Importing a crop is deliberate. Confirming a cluster records who attributed a face to
 * whom and nothing else; deciding what somebody looks like is a separate act by whoever is
 * looking at the person.
 */
export default function PersonDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();

  const [person, setPerson] = useState<PersonResponse | null>(null);
  const [images, setImages] = useState<PersonImageResponse[]>([]);
  const [candidates, setCandidates] = useState<CropCandidate[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const reloadImages = useCallback(async () => {
    if (!token || !id) return;
    const [fresh, list] = await Promise.all([loadPerson(token, id), listPersonImages(token, id)]);
    setPerson(fresh);
    setImages(list.data ?? []);
  }, [token, id]);

  useEffect(() => {
    if (!token || !id) return;
    let cancelled = false;
    setLoading(true);

    Promise.all([loadPerson(token, id), listPersonImages(token, id), listPersonClusters(token, id)])
      .then(async ([fresh, list, clusters]) => {
        if (cancelled) return;
        setPerson(fresh);
        setImages(list.data ?? []);

        // The crops on offer are the faces of the clusters already confirmed to this person, which is
        // the only set that is certainly them.
        const members = await Promise.all(
          (clusters.data ?? []).map(c => listClusterMembers(token, c.uuid).catch(() => ({ members: [] as ClusterMemberModel[], total: 0 }))),
        );
        if (cancelled) return;
        const seen = new Set<string>();
        const crops: CropCandidate[] = [];
        for (const m of members.flatMap(r => r.members ?? [])) {
          if (!m.detectionUuid || !m.assetUuid || seen.has(m.detectionUuid)) continue;
          seen.add(m.detectionUuid);
          crops.push({ detectionUuid: m.detectionUuid, assetUuid: m.assetUuid });
        }
        setCandidates(crops);
      })
      .catch(() => {
        if (!cancelled) showToast(t("person.toast.loadFailed"), "error");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [token, id, showToast, t]);

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Reset first, so picking the same file again re-fires the change event.
    e.target.value = "";
    if (!file || !token || !id) return;
    setBusy(true);
    try {
      await uploadPersonImage(token, id, file);
      await reloadImages();
      showToast(t("person.toast.imageUploaded"), "success");
    } catch {
      showToast(t("person.toast.uploadFailed"), "error");
    } finally {
      setBusy(false);
    }
  };

  const handleImport = async (detectionUuid: string) => {
    if (!token || !id) return;
    setBusy(true);
    try {
      await importPersonImage(token, id, detectionUuid);
      await reloadImages();
      showToast(t("person.toast.cropImported"), "success");
    } catch {
      showToast(t("person.toast.importFailed"), "error");
    } finally {
      setBusy(false);
    }
  };

  const handleSetAvatar = async (imageUuid: string) => {
    if (!token || !id) return;
    setBusy(true);
    try {
      await setPersonAvatar(token, id, imageUuid);
      await reloadImages();
      showToast(t("person.toast.avatarSet"), "success");
    } catch {
      showToast(t("person.toast.avatarFailed"), "error");
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteImage = async (imageUuid: string) => {
    if (!token || !id) return;
    setBusy(true);
    try {
      await deletePersonImage(token, id, imageUuid);
      await reloadImages();
      showToast(t("person.toast.imageDeleted"), "success");
    } catch {
      showToast(t("person.toast.deleteFailed"), "error");
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return (
      <Box sx={{ display: "flex", justifyContent: "center", py: 8 }} data-testid="person-detail-loading">
        <CircularProgress size={28} />
      </Box>
    );
  }

  if (!person) {
    return (
      <Box sx={{ p: 3 }}>
        <EmptyState
          icon={PhotoLibraryOutlined}
          title={t("person.empty.notFound")}
          description={t("person.empty.notFoundHint")}
          actionLabel={t("person.button.back")}
          onAction={() => navigate("/detection")}
          testId="person-detail-missing"
        />
      </Box>
    );
  }

  const displayName = [person.firstname, person.lastname].filter(Boolean).join(" ") || person.alias;

  return (
    <Box sx={{ p: 3, display: "flex", flexDirection: "column", gap: 3 }} data-testid="person-detail">
      <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
        <IconButton size="small" onClick={() => navigate("/detection")} data-testid="person-detail-back">
          <ArrowBackOutlined sx={{ fontSize: 18 }} />
        </IconButton>
        <Avatar src={person.avatarUrl ?? undefined} sx={{ width: 72, height: 72 }} data-testid="person-detail-avatar" />
        <Box sx={{ flex: 1 }}>
          <Typography variant="h6" fontWeight={700} data-testid="person-detail-name" sx={{ color: tokens.text.primary }}>
            {displayName}
          </Typography>
          <Typography variant="body2" data-testid="person-detail-alias" sx={{ color: tokens.text.secondary }}>
            {person.alias}
          </Typography>
        </Box>
        <Button
          component="label"
          variant="contained"
          size="small"
          startIcon={<UploadOutlined />}
          disabled={busy}
          data-testid="person-image-upload"
        >
          {t("person.button.uploadImage")}
          <input type="file" hidden accept="image/*" ref={fileInputRef} onChange={handleUpload} data-testid="person-image-input" />
        </Button>
      </Box>

      <Box>
        <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1, color: tokens.text.primary }}>
          {t("person.label.images")}
        </Typography>
        {images.length === 0 ? (
          <EmptyState
            icon={PhotoLibraryOutlined}
            title={t("person.empty.images")}
            description={t("person.empty.imagesHint")}
            testId="person-images-empty"
            compact
          />
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
            {images.map(image => (
              <Paper
                key={image.uuid}
                elevation={0}
                data-testid="person-image-card"
                data-avatar={image.avatar ? "true" : "false"}
                sx={{
                  bgcolor: tokens.bg.elevated,
                  border: `1px solid ${image.avatar ? tokens.primary.main : tokens.border.subtle}`,
                  borderRadius: tokens.radius.lg,
                  overflow: "hidden",
                }}
              >
                <Box sx={{ position: "relative", paddingTop: "100%" }}>
                  <Box
                    component="img"
                    src={image.url}
                    alt={image.filename}
                    loading="lazy"
                    data-testid="person-image"
                    sx={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }}
                  />
                </Box>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, px: 1, py: 0.75 }}>
                  <Typography variant="caption" noWrap sx={{ flex: 1, color: tokens.text.secondary }}>
                    {image.filename}
                  </Typography>
                  {image.avatar ? (
                    <Chip
                      label={t("person.label.avatar")}
                      size="small"
                      icon={<CheckCircle sx={{ fontSize: 14 }} />}
                      data-testid="person-image-is-avatar"
                      sx={{ height: 20, fontSize: "0.62rem" }}
                    />
                  ) : (
                    <Tooltip title={t("person.tooltip.makeAvatar")}>
                      <span>
                        <IconButton
                          size="small"
                          disabled={busy}
                          onClick={() => handleSetAvatar(image.uuid)}
                          data-testid="person-image-make-avatar"
                        >
                          <CheckCircleOutlined sx={{ fontSize: 16 }} />
                        </IconButton>
                      </span>
                    </Tooltip>
                  )}
                  <Tooltip title={t("person.tooltip.deleteImage")}>
                    <span>
                      <IconButton size="small" disabled={busy} onClick={() => handleDeleteImage(image.uuid)} data-testid="person-image-delete">
                        <DeleteOutlined sx={{ fontSize: 16 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </Box>
              </Paper>
            ))}
          </Box>
        )}
      </Box>

      {candidates.length > 0 && (
        <Box data-testid="person-crop-picker">
          <Typography variant="subtitle2" fontWeight={700} sx={{ color: tokens.text.primary }}>
            {t("person.label.faces")}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.secondary, display: "block", mb: 1 }}>
            {t("person.label.facesHint")}
          </Typography>
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
            {candidates.map(candidate => (
              <Tooltip key={candidate.detectionUuid} title={t("person.tooltip.importCrop")}>
                <Box
                  role="button"
                  tabIndex={0}
                  data-testid="person-crop-candidate"
                  onClick={() => handleImport(candidate.detectionUuid)}
                  onKeyDown={e => {
                    if (e.key === "Enter" || e.key === " ") handleImport(candidate.detectionUuid);
                  }}
                  sx={{ cursor: busy ? "default" : "pointer", opacity: busy ? 0.5 : 1, borderRadius: 1 }}
                >
                  <FaceCrop assetUuid={candidate.assetUuid} detectionUuid={candidate.detectionUuid} size={64} rounded={false} />
                </Box>
              </Tooltip>
            ))}
          </Box>
        </Box>
      )}
    </Box>
  );
}
