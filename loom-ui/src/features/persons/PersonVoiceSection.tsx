import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert, Box, Button, Chip, CircularProgress, IconButton, Paper, Tooltip, Typography,
} from "@mui/material";
import {
  DeleteOutlined, GraphicEqOutlined, SaveAltOutlined, StarBorderRounded, StarRounded, UploadOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useFailure } from "../../context/FailureContext";
import { useToast } from "../../context/ToastContext";
import EmptyState from "../../components/EmptyState";
import LoadFailure from "../../components/LoadFailure";
import AuthedAudioPlayer from "../../components/AuthedAudioPlayer";
import type { ClusterResponse } from "../../api/clusters";
import {
  deletePersonVoiceSample, importPersonVoiceSample, listPersonVoiceSamples, loadPerson, setPersonHeroVoice,
  uploadPersonVoiceSample,
  type PersonResponse, type PersonVoiceSampleResponse,
} from "../../api/persons";
import { loadAsset } from "../../api/assets";
import { fetchAudioClip, fetchAuthedBlob } from "../../api/media";
import {
  clipRange, formatClock, longestTurn, SPEAKER_PREVIEW_MS, speakingTimeMs, totalReferenceMs, turnsFromMeta,
  VOICE_REFERENCE_MIN_MS,
} from "../speakers/speakerTurns";

interface PersonVoiceSectionProps {
  personUuid: string;
  /** The speaker clusters confirmed to this person - the material a sample can be cut from. */
  speakerClusters: ClusterResponse[];
  /** The person as the server now has it, after the hero voice changed. */
  onPersonChanged: (person: PersonResponse) => void;
}

/**
 * A person's voice: the recordings recognition compares new speakers with.
 *
 * The counterpart of their pictures, with the same ownership rule - a sample belongs to the person,
 * not to the asset it was cut from, so deleting a video does not make somebody unrecognisable. Two
 * ways in: upload a recording, or cut one from a speaker already confirmed to this person.
 *
 * Each sample lists the prints the voiceprint node computed from it, one per model, because a
 * sample that produced no vector ("no speech found") silently contributes nothing to recognition
 * and the only place anybody would notice is here.
 */
export default function PersonVoiceSection({ personUuid, speakerClusters, onPersonChanged }: PersonVoiceSectionProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { reportFailure } = useFailure();
  const { showToast } = useToast();

  const [samples, setSamples] = useState<PersonVoiceSampleResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [busy, setBusy] = useState(false);
  const [assetNames, setAssetNames] = useState<Record<string, string>>({});

  const reload = useCallback(async () => {
    if (!token) return;
    const list = await listPersonVoiceSamples(token, personUuid);
    setSamples(list.data ?? []);
  }, [token, personUuid]);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    setSamples([]);
    listPersonVoiceSamples(token, personUuid)
      .then(list => {
        if (!cancelled) setSamples(list.data ?? []);
      })
      .catch(e => {
        if (!cancelled) setLoadError(reportFailure("loadVoiceSamples", e).message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token, personUuid, reloadToken, reportFailure]);

  // Asset names for "from <asset> at 1:05" and for the speaker list. A nicety, so failures are dropped.
  const assetUuids = useMemo(() => [...new Set([
    ...samples.map(s => s.sourceAssetUuid),
    ...speakerClusters.map(c => c.assetUuid),
  ].filter((u): u is string => !!u))].sort().join(","), [samples, speakerClusters]);

  useEffect(() => {
    if (!token || !assetUuids) return;
    let cancelled = false;
    const missing = assetUuids.split(",").filter(u => !(u in assetNames));
    if (missing.length === 0) return;
    Promise.all(missing.map(u => loadAsset(token, u).then(a => [u, a.file?.filename ?? ""] as const).catch(() => [u, ""] as const)))
      .then(entries => {
        if (cancelled) return;
        setAssetNames(prev => ({ ...prev, ...Object.fromEntries(entries) }));
      });
    return () => {
      cancelled = true;
    };
    // `assetNames` is what this effect fills; depending on it would re-run it for its own answer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, assetUuids]);

  const assetLabel = (uuid: string | null | undefined) => (uuid && assetNames[uuid]) || uuid || "";

  const total = totalReferenceMs(samples);
  const hero = samples.find(s => s.hero);

  /** One mutation, then the list as the server now has it. */
  const mutate = async (action: string, work: () => Promise<void>, success: string) => {
    if (!token || busy) return;
    setBusy(true);
    try {
      await work();
      await reload();
      showToast(success, "success");
    } catch (e) {
      reportFailure(action, e);
    } finally {
      setBusy(false);
    }
  };

  const handleUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Reset first, so picking the same file again re-fires the change event.
    e.target.value = "";
    if (!file) return;
    void mutate("uploadVoiceSample", async () => { await uploadPersonVoiceSample(token!, personUuid, file); }, t("person.voice.toast.uploaded"));
  };

  const handleImport = (clusterUuid: string) =>
    mutate("importVoiceSample", async () => { await importPersonVoiceSample(token!, personUuid, { clusterUuid }); }, t("person.voice.toast.imported"));

  const handleHero = (sampleUuid: string | null) =>
    mutate("setHeroVoice", async () => { onPersonChanged(await setPersonHeroVoice(token!, personUuid, sampleUuid)); },
      sampleUuid ? t("person.voice.toast.heroSet") : t("person.voice.toast.heroCleared"));

  const handleDelete = (sampleUuid: string) =>
    mutate("deleteVoiceSample", async () => {
      await deletePersonVoiceSample(token!, personUuid, sampleUuid);
      // The server leaves a person whose hero sample this was without one; re-read them so the
      // player beside the avatar does not go on offering a recording that is gone.
      if (hero?.uuid === sampleUuid) onPersonChanged(await loadPerson(token!, personUuid));
    }, t("person.voice.toast.deleted"));

  const sourceLine = (s: PersonVoiceSampleResponse) => {
    const length = s.durationMs ? formatClock(s.durationMs) : t("person.voice.label.unknownLength");
    const origin = s.sourceAssetUuid
      ? t("person.voice.label.fromAsset", { asset: assetLabel(s.sourceAssetUuid), at: formatClock(s.sourceTimeFrom) })
      : t("person.voice.label.uploaded");
    return `${length} · ${origin}`;
  };

  return (
    <Box data-testid="person-voice-section">
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1, flexWrap: "wrap" }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ color: tokens.text.primary }}>
          {t("person.voice.title")}
        </Typography>
        <Typography variant="caption" data-testid="person-voice-total" data-total-ms={total} sx={{ color: tokens.text.secondary }}>
          {t("person.voice.label.total", { time: formatClock(total) })}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {hero && (
          <Button size="small" disabled={busy} onClick={() => handleHero(null)} data-testid="person-voice-clear-hero"
            sx={{ textTransform: "none", fontSize: "0.75rem" }}>
            {t("person.voice.button.clearHero")}
          </Button>
        )}
        <Button component="label" variant="outlined" size="small" startIcon={<UploadOutlined />} disabled={busy}
          data-testid="person-voice-upload" sx={{ textTransform: "none" }}>
          {t("person.voice.button.upload")}
          <input type="file" hidden accept="audio/*,video/*" onChange={handleUpload} data-testid="person-voice-input" />
        </Button>
      </Box>

      {/* Only once there is something to judge: a person nobody has recorded yet is not short of
          speech, they simply have no voice here - the empty state says that. */}
      {!loading && !loadError && samples.length > 0 && total < VOICE_REFERENCE_MIN_MS && (
        <Alert severity="warning" data-testid="person-voice-warning" sx={{ mb: 1.5, py: 0.25, fontSize: "0.8rem" }}>
          {t("person.voice.warning.short")}
        </Alert>
      )}

      {loading && <CircularProgress size={20} data-testid="person-voice-loading" />}
      {!loading && loadError && (
        <LoadFailure message={loadError} onRetry={() => setReloadToken(n => n + 1)} testId="person-voice-load-failure" />
      )}
      {!loading && !loadError && samples.length === 0 && (
        <EmptyState icon={GraphicEqOutlined} title={t("person.voice.empty.title")} description={t("person.voice.empty.hint")}
          testId="person-voice-empty" compact />
      )}
      {!loading && !loadError && samples.length > 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          {samples.map(sample => (
            <Paper key={sample.uuid} elevation={0} data-testid="person-voice-sample" data-sample-uuid={sample.uuid}
              data-hero={sample.hero ? "true" : "false"}
              sx={{
                display: "flex", alignItems: "center", gap: 1.5, px: 1.5, py: 1, flexWrap: "wrap",
                bgcolor: tokens.bg.elevated, borderRadius: tokens.radius.md,
                border: `1px solid ${sample.hero ? tokens.primary.main : tokens.border.subtle}`,
              }}>
              <AuthedAudioPlayer sourceKey={sample.uuid} testId="person-voice-play" width={240}
                label={t("person.voice.tooltip.play", { name: sample.filename })}
                load={() => fetchAuthedBlob(token!, sample.url)} />
              <Box sx={{ flex: 1, minWidth: 180 }}>
                <Typography variant="body2" noWrap sx={{ fontSize: "0.82rem", color: tokens.text.primary }} data-testid="person-voice-filename">
                  {sample.filename}
                </Typography>
                <Typography variant="caption" sx={{ color: tokens.text.secondary }} data-testid="person-voice-source">
                  {sourceLine(sample)}
                </Typography>
                <Box sx={{ display: "flex", gap: 0.5, mt: 0.5, flexWrap: "wrap" }}>
                  {sample.prints.length === 0 && (
                    <Tooltip disableInteractive title={t("person.voice.tooltip.notEnrolled")}>
                      <Chip size="small" label={t("person.voice.label.notEnrolled")} data-testid="person-voice-print-none"
                        sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.overlay }} />
                    </Tooltip>
                  )}
                  {sample.prints.map(print => (
                    <Tooltip disableInteractive key={print.uuid}
                      title={print.usable
                        ? t("person.voice.tooltip.printUsable", { model: print.model, dimensions: print.dimensions ?? "?" })
                        : t("person.voice.tooltip.printUnusable", { model: print.model, error: print.error || t("person.voice.label.noReason") })}>
                      <Chip size="small" label={print.model} data-testid="person-voice-print" data-usable={print.usable ? "true" : "false"}
                        sx={{
                          height: 18, fontSize: "0.62rem", bgcolor: "transparent",
                          color: print.usable ? tokens.accent.green : tokens.accent.red,
                          border: `1px solid ${print.usable ? tokens.accent.green : tokens.accent.red}66`,
                        }} />
                    </Tooltip>
                  ))}
                </Box>
              </Box>
              {sample.hero ? (
                <Chip size="small" icon={<StarRounded sx={{ fontSize: 14 }} />} label={t("person.voice.label.hero")}
                  data-testid="person-voice-is-hero" sx={{ height: 22, fontSize: "0.65rem" }} />
              ) : (
                <Tooltip disableInteractive title={t("person.voice.tooltip.makeHero")}>
                  <span>
                    <IconButton size="small" disabled={busy} onClick={() => handleHero(sample.uuid)} data-testid="person-voice-make-hero"
                      aria-label={t("person.voice.tooltip.makeHero")}>
                      <StarBorderRounded sx={{ fontSize: 18 }} />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
              <Tooltip disableInteractive title={t("person.voice.tooltip.delete")}>
                <span>
                  <IconButton size="small" disabled={busy} onClick={() => handleDelete(sample.uuid)} data-testid="person-voice-delete"
                    aria-label={t("person.voice.tooltip.delete")}>
                    <DeleteOutlined sx={{ fontSize: 16 }} />
                  </IconButton>
                </span>
              </Tooltip>
            </Paper>
          ))}
        </Box>
      )}

      {speakerClusters.length > 0 && (
        <Box data-testid="person-speaker-import-list" sx={{ mt: 2 }}>
          <Typography variant="subtitle2" fontWeight={700} sx={{ color: tokens.text.primary }}>
            {t("person.voice.label.importTitle")}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.secondary, display: "block", mb: 1 }}>
            {t("person.voice.label.importHint")}
          </Typography>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
            {speakerClusters.map(cluster => {
              const turns = turnsFromMeta(cluster.meta);
              const longest = longestTurn(turns);
              const preview = longest ? clipRange(longest, SPEAKER_PREVIEW_MS) : null;
              return (
                <Box key={cluster.uuid} data-testid="person-speaker-candidate" data-cluster-id={cluster.uuid}
                  sx={{ display: "flex", alignItems: "center", gap: 1, px: 1, py: 0.5, borderRadius: tokens.radius.sm, bgcolor: tokens.bg.elevated }}>
                  {cluster.assetUuid && preview && (
                    <AuthedAudioPlayer sourceKey={cluster.uuid} testId="person-speaker-play" width={200}
                      label={t("speakers.tooltip.play")}
                      load={() => fetchAudioClip(token!, cluster.assetUuid!, preview.fromMs, preview.toMs)} />
                  )}
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography variant="body2" noWrap sx={{ fontSize: "0.8rem", color: tokens.text.primary }}>
                      {assetLabel(cluster.assetUuid) || cluster.name}
                    </Typography>
                    <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
                      {t("speakers.label.stats", { time: formatClock(speakingTimeMs(cluster.meta, turns)), count: turns.length })}
                    </Typography>
                  </Box>
                  <Button size="small" startIcon={<SaveAltOutlined sx={{ fontSize: 14 }} />} disabled={busy}
                    onClick={() => handleImport(cluster.uuid)} data-testid="person-speaker-import" sx={{ textTransform: "none", fontSize: "0.75rem" }}>
                    {t("person.voice.button.import")}
                  </Button>
                </Box>
              );
            })}
          </Box>
        </Box>
      )}
    </Box>
  );
}
