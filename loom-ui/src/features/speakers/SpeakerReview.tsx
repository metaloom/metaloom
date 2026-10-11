import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Autocomplete, Box, Button, Chip, CircularProgress, Collapse, Dialog, DialogActions, DialogContent,
  DialogTitle, IconButton, InputAdornment, Paper, TextField, Tooltip, Typography,
} from "@mui/material";
import {
  BlockOutlined, CheckOutlined, ExpandLessOutlined, ExpandMoreOutlined, LinkOutlined,
  PersonAddAlt1Outlined, RecordVoiceOverOutlined, SaveAltOutlined, SearchOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { tokens } from "../../theme";
import type { Person } from "../../types";
import { useAuth } from "../../context/AuthContext";
import { useFailure } from "../../context/FailureContext";
import { useToast } from "../../context/ToastContext";
import {
  CLUSTER_STATUS_CONFIRMED, CLUSTER_STATUS_PENDING, CLUSTER_STATUS_REJECTED, CLUSTER_TYPE_SPEAKER,
  confirmCluster, detachClusterPerson, listClusterMembers, listClusters, rejectCluster,
  type ClusterConfirmRequest, type ClusterResponse,
} from "../../api/clusters";
import { importPersonVoiceSample, listPersons } from "../../api/persons";
import { loadAsset } from "../../api/assets";
import { AUDIO_CLIP_MAX_MS, fetchAudioClip } from "../../api/media";
import { PAGE_SIZE } from "../../hooks/pagedList";
import { ListFilterSelect } from "../../components/ListControls";
import LoadFailure from "../../components/LoadFailure";
import AuthedAudioPlayer from "../../components/AuthedAudioPlayer";
import { toUiPerson } from "../faceDetection/personMapping";
import {
  clipRange, formatClock, longestTurn, mergeWindowsIntoTurns, scorePercent, speakingTimeMs,
  SPEAKER_PREVIEW_MS, turnsFromMeta, type SpeakerTurn,
} from "./speakerTurns";

/**
 * The Speakers tab of the detection screen: one card per voice the voiceprint node found.
 *
 * The voice counterpart of the face clusters, with the same verdicts - confirm to a person, reject,
 * take a person back off - but reviewed by ear. A speaker cluster lives within one asset, so a card
 * names the asset, says how long the voice spoke and in how many turns, and plays the longest turn.
 *
 * Confirming does not give the person a voice. Recognition compares new speakers with each
 * person's *voice samples*, and those are a deliberate copy ("Save as voice sample"), for the same
 * reason importing a face crop into a person's pictures is: attributing a voice records a verdict,
 * deciding what somebody sounds like is a separate act.
 */
export default function SpeakerReview() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { reportFailure } = useFailure();
  const { showToast } = useToast();

  const [clusters, setClusters] = useState<ClusterResponse[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [assetNames, setAssetNames] = useState<Record<string, string>>({});
  const [status, setStatus] = useState("");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [assignFor, setAssignFor] = useState<ClusterResponse | null>(null);
  const [assignPerson, setAssignPerson] = useState<Person | null>(null);
  const [createFor, setCreateFor] = useState<ClusterResponse | null>(null);
  const [newAlias, setNewAlias] = useState("");

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    // The status filter goes to the server: a long-running library has thousands of decided
    // speakers, and filtering a first page of them locally would hide the pending ones behind it.
    Promise.all([
      listClusters(token, { limit: PAGE_SIZE }, { type: CLUSTER_TYPE_SPEAKER, status: status || undefined }),
      listPersons(token, { limit: PAGE_SIZE }),
    ])
      .then(([clustersResp, personsResp]) => {
        if (cancelled) return;
        const loaded = clustersResp.data ?? [];
        setClusters(loaded);
        setPersons((personsResp.data ?? []).map(p => toUiPerson(p)));

        // Asset names are a nicety: a card without one says the uuid, so failures are dropped.
        const uuids = [...new Set(loaded.map(c => c.assetUuid).filter((u): u is string => !!u))];
        Promise.all(uuids.map(u => loadAsset(token, u).then(a => [u, a.file?.filename ?? ""] as const).catch(() => null)))
          .then(entries => {
            if (cancelled) return;
            const names: Record<string, string> = {};
            for (const e of entries) if (e && e[1]) names[e[0]] = e[1];
            setAssetNames(names);
          });
      })
      .catch(e => {
        if (!cancelled) setLoadError(reportFailure("loadSpeakers", e).message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token, status, reloadToken, reportFailure]);

  const personName = useCallback((c: ClusterResponse) => {
    if (!c.personUuid) return undefined;
    return c.personName || persons.find(p => p.id === c.personUuid)?.name || c.personUuid;
  }, [persons]);

  const assetLabel = useCallback((c: ClusterResponse) =>
    (c.assetUuid && assetNames[c.assetUuid]) || c.assetUuid || t("speakers.label.noAsset"), [assetNames, t]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return clusters;
    return clusters.filter(c =>
      [assetLabel(c), c.name, personName(c), c.suggestedPersonName]
        .some(v => !!v && v.toLowerCase().includes(q)));
  }, [clusters, query, assetLabel, personName]);

  /** Swap in what the server stored for one cluster - never what was asked for. */
  const replace = (updated: ClusterResponse) =>
    setClusters(prev => prev.map(c => (c.uuid === updated.uuid ? { ...c, ...updated } : c)));

  /** One mutation on one card. False when it failed, so a dialog can stay open for a retry. */
  const runOn = async (clusterUuid: string, action: string, work: () => Promise<void>): Promise<boolean> => {
    setBusyId(clusterUuid);
    try {
      await work();
      return true;
    } catch (e) {
      reportFailure(action, e);
      return false;
    } finally {
      setBusyId(null);
    }
  };

  const confirm = (cluster: ClusterResponse, request: ClusterConfirmRequest, fallbackName?: string) =>
    runOn(cluster.uuid, "confirmCluster", async () => {
      const confirmed = await confirmCluster(token!, cluster.uuid, request);
      const pid = confirmed.personUuid;
      replace({ ...confirmed, personName: confirmed.personName ?? fallbackName });
      // A person created by this confirm joins the list the assign dialog offers.
      if (pid && fallbackName) {
        setPersons(prev => (prev.some(p => p.id === pid) ? prev
          : [...prev, { id: pid, name: fallbackName, description: request.alias ?? "", avatarUrl: "", clusterIds: [], createdAt: new Date().toISOString() }]));
      }
    });

  const handleAssign = async () => {
    if (!assignFor || !assignPerson) return;
    if (!(await confirm(assignFor, { personUuid: assignPerson.id }, assignPerson.name))) return;
    setAssignFor(null);
    setAssignPerson(null);
  };

  const handleCreate = async () => {
    const alias = newAlias.trim();
    if (!createFor || !alias) return;
    if (!(await confirm(createFor, { alias }, alias))) return;
    setCreateFor(null);
    setNewAlias("");
  };

  const handleReject = (cluster: ClusterResponse) =>
    runOn(cluster.uuid, "rejectCluster", async () => replace(await rejectCluster(token!, cluster.uuid)));

  const handleDetach = (cluster: ClusterResponse) =>
    runOn(cluster.uuid, "detachClusterPerson", async () => {
      const detached = await detachClusterPerson(token!, cluster.uuid);
      // Spread over the old row, so clear the person explicitly: an absent field would not.
      replace({ ...detached, personUuid: detached.personUuid ?? undefined, personName: detached.personName ?? null });
    });

  const handleSaveSample = (cluster: ClusterResponse, range?: { fromMs: number; toMs: number }) =>
    runOn(cluster.uuid, "importVoiceSample", async () => {
      if (!cluster.personUuid) return;
      await importPersonVoiceSample(token!, cluster.personUuid, range
        ? { clusterUuid: cluster.uuid, timeFrom: range.fromMs, timeTo: range.toMs }
        : { clusterUuid: cluster.uuid });
      showToast(t("speakers.toast.sampleSaved", { name: personName(cluster) }), "success");
    });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }} data-testid="speaker-review">
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("speakers.search.placeholder")}
          size="small"
          data-testid="speakers-search"
          sx={{ flex: 1, maxWidth: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
        <ListFilterSelect value={status} onChange={setStatus}
          options={[
            { value: CLUSTER_STATUS_PENDING, label: t("speakers.status.PENDING") },
            { value: CLUSTER_STATUS_CONFIRMED, label: t("speakers.status.CONFIRMED") },
            { value: CLUSTER_STATUS_REJECTED, label: t("speakers.status.REJECTED") },
          ]}
          allLabel={t("speakers.filter.anyStatus")} testId="speakers-filter-status" minWidth={150} />
        {!loading && !loadError && (
          <Typography variant="caption" sx={{ ml: "auto", color: tokens.text.tertiary }} data-testid="speakers-count">
            {t("speakers.count", { count: filtered.length })}
          </Typography>
        )}
      </Box>

      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {loading && (
          <Box sx={{ display: "flex", justifyContent: "center", py: 6 }} data-testid="speakers-loading">
            <CircularProgress size={28} />
          </Box>
        )}
        {!loading && loadError && <LoadFailure message={loadError} onRetry={() => setReloadToken(n => n + 1)} testId="speakers-load-failure" />}
        {!loading && !loadError && filtered.length === 0 && (
          <Box data-testid="speakers-empty" sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
            <RecordVoiceOverOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">{t("speakers.empty.title")}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ maxWidth: 420, textAlign: "center" }}>{t("speakers.empty.hint")}</Typography>
          </Box>
        )}
        {!loading && !loadError && filtered.length > 0 && (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(340px, 1fr))", gap: 2, alignItems: "start" }}>
            {filtered.map(cluster => (
              <SpeakerCard
                key={cluster.uuid}
                cluster={cluster}
                assetLabel={assetLabel(cluster)}
                personName={personName(cluster)}
                busy={busyId === cluster.uuid}
                onConfirmSuggestion={() => cluster.suggestedPersonUuid
                  && confirm(cluster, { personUuid: cluster.suggestedPersonUuid }, cluster.suggestedPersonName ?? undefined)}
                onAssign={() => { setAssignFor(cluster); setAssignPerson(null); }}
                onCreate={() => { setCreateFor(cluster); setNewAlias(""); }}
                onReject={() => handleReject(cluster)}
                onDetach={() => handleDetach(cluster)}
                onSaveSample={range => handleSaveSample(cluster, range)}
              />
            ))}
          </Box>
        )}
      </Box>

      {/* Assign to somebody who already exists. An Autocomplete over objects rather than freeSolo
          names: creating a person is the other button, so a typo here cannot quietly invent one.
          `autoFocus` without `openOnFocus` - together they make the first click close the list. */}
      <Dialog open={!!assignFor} onClose={() => setAssignFor(null)} maxWidth="xs" fullWidth
        PaperProps={{ "data-testid": "speaker-assign-dialog" } as React.ComponentProps<typeof Dialog>["PaperProps"]}>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("speakers.dialog.assign")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important", minHeight: 80 }}>
          <Autocomplete
            options={persons}
            value={assignPerson}
            onChange={(_, value) => setAssignPerson(value)}
            getOptionLabel={p => p.name}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            size="small"
            renderInput={params => (
              <TextField {...params} autoFocus label={t("speakers.dialog.selectPerson")}
                inputProps={{ ...params.inputProps, "data-testid": "speaker-assign-input" }} />
            )}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAssignFor(null)} size="small">{t("speakers.button.cancel")}</Button>
          <Button onClick={handleAssign} variant="contained" size="small" data-testid="speaker-assign-save" disabled={!assignPerson}>
            {t("speakers.button.assign")}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!createFor} onClose={() => setCreateFor(null)} maxWidth="xs" fullWidth
        onKeyDown={e => {
          if (e.key !== "Enter" || e.shiftKey) return;
          e.preventDefault();
          if (newAlias.trim()) void handleCreate();
        }}
        PaperProps={{ "data-testid": "speaker-create-dialog" } as React.ComponentProps<typeof Dialog>["PaperProps"]}>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("speakers.dialog.create")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          <TextField label={t("speakers.dialog.alias")} value={newAlias} onChange={e => setNewAlias(e.target.value)}
            size="small" fullWidth autoFocus inputProps={{ "data-testid": "speaker-create-alias" }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateFor(null)} size="small">{t("speakers.button.cancel")}</Button>
          <Button onClick={handleCreate} variant="contained" size="small" data-testid="speaker-create-save" disabled={!newAlias.trim()}>
            {t("speakers.button.create")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

interface SpeakerCardProps {
  cluster: ClusterResponse;
  assetLabel: string;
  personName?: string;
  busy: boolean;
  onConfirmSuggestion: () => void;
  onAssign: () => void;
  onCreate: () => void;
  onReject: () => void;
  onDetach: () => void;
  /** Save a stretch as a voice sample of the confirmed person; no range takes the longest turn. */
  onSaveSample: (range?: { fromMs: number; toMs: number }) => void;
}

/** Read per render rather than once at import: the tokens follow the light/dark switch. */
function statusColor(status: string): string | undefined {
  if (status === CLUSTER_STATUS_PENDING) return tokens.accent.amber;
  if (status === CLUSTER_STATUS_CONFIRMED) return tokens.accent.green;
  if (status === CLUSTER_STATUS_REJECTED) return tokens.accent.red;
  return undefined;
}

function SpeakerCard({ cluster, assetLabel, personName, busy, onConfirmSuggestion, onAssign, onCreate, onReject, onDetach, onSaveSample }: SpeakerCardProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const metaTurns = useMemo(() => turnsFromMeta(cluster.meta), [cluster.meta]);
  const [expanded, setExpanded] = useState(false);
  /** Turns merged from the member windows, once fetched. Null until somebody asks. */
  const [memberTurns, setMemberTurns] = useState<SpeakerTurn[] | null>(null);
  const [turnsLoading, setTurnsLoading] = useState(false);

  const reviewStatus = cluster.reviewStatus ?? CLUSTER_STATUS_PENDING;
  const confirmed = !!cluster.personUuid;
  const turns = memberTurns && memberTurns.length > 0 ? memberTurns : metaTurns;
  const total = speakingTimeMs(cluster.meta, turns);
  const suggestion = !confirmed && reviewStatus === CLUSTER_STATUS_PENDING && cluster.suggestedPersonUuid
    ? { name: cluster.suggestedPersonName || cluster.suggestedPersonUuid, pct: scorePercent(cluster.suggestedScore) }
    : null;

  /** The member windows merged into turns; the node's own segments when the windows carry no times. */
  const loadTurns = useCallback(async (): Promise<SpeakerTurn[]> => {
    if (memberTurns) return memberTurns.length > 0 ? memberTurns : metaTurns;
    if (!token) return metaTurns;
    const resp = await listClusterMembers(token, cluster.uuid);
    const merged = mergeWindowsIntoTurns(resp.members ?? []);
    setMemberTurns(merged);
    return merged.length > 0 ? merged : metaTurns;
  }, [token, cluster.uuid, memberTurns, metaTurns]);

  const toggleExpanded = async () => {
    const next = !expanded;
    setExpanded(next);
    if (next && memberTurns === null) {
      setTurnsLoading(true);
      try {
        await loadTurns();
      } catch {
        // The node's own segments are still there to show.
        setMemberTurns([]);
      } finally {
        setTurnsLoading(false);
      }
    }
  };

  /** The card's play button: the longest turn, capped, fetched only when pressed. */
  const playPreview = async () => {
    if (!token || !cluster.assetUuid) throw new Error("no asset");
    const known = metaTurns.length > 0 ? metaTurns : await loadTurns();
    const turn = longestTurn(known);
    if (!turn) throw new Error("no turns");
    const { fromMs, toMs } = clipRange(turn, SPEAKER_PREVIEW_MS);
    return fetchAudioClip(token, cluster.assetUuid, fromMs, toMs);
  };

  return (
    <Paper elevation={0} data-testid="speaker-card" data-cluster-id={cluster.uuid} data-review-status={reviewStatus}
      sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg, overflow: "hidden", opacity: busy ? 0.7 : 1 }}>
      <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1.25, px: 2, pt: 1.5, pb: 1 }}>
        <RecordVoiceOverOutlined sx={{ fontSize: 22, color: tokens.text.tertiary, mt: 0.25 }} />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Tooltip disableInteractive title={cluster.assetUuid ?? ""}>
            <Typography variant="body2" fontWeight={600} noWrap data-testid="speaker-asset" sx={{ fontSize: "0.85rem", color: tokens.text.primary }}>
              {assetLabel}
            </Typography>
          </Tooltip>
          {cluster.name && (
            <Typography variant="caption" noWrap data-testid="speaker-name" sx={{ display: "block", color: tokens.text.secondary, fontSize: "0.72rem" }}>
              {cluster.name}
            </Typography>
          )}
          <Typography variant="caption" data-testid="speaker-stats" sx={{ display: "block", color: tokens.text.tertiary, fontSize: "0.72rem" }}>
            {t("speakers.label.stats", { time: formatClock(total), count: turns.length })}
          </Typography>
        </Box>
        <Chip size="small" label={t(`speakers.status.${reviewStatus}`, { defaultValue: reviewStatus })} data-testid="speaker-status"
          sx={{ height: 20, fontSize: "0.65rem", color: statusColor(reviewStatus) ?? tokens.text.secondary, border: `1px solid ${statusColor(reviewStatus) ?? tokens.border.subtle}`, bgcolor: "transparent" }} />
      </Box>

      {suggestion && (
        <Box data-testid="speaker-suggestion" sx={{ display: "flex", alignItems: "center", gap: 1, mx: 2, mb: 1, px: 1, py: 0.5, borderRadius: tokens.radius.sm, bgcolor: `${tokens.accent.blue}12`, border: `1px solid ${tokens.accent.blue}33` }}>
          <Typography variant="caption" sx={{ flex: 1, color: tokens.text.secondary }} data-testid="speaker-suggestion-label">
            {t("speakers.suggestion.looksLike", { name: suggestion.name, score: suggestion.pct })}
          </Typography>
          <Button size="small" startIcon={<CheckOutlined sx={{ fontSize: 14 }} />} disabled={busy} onClick={onConfirmSuggestion}
            data-testid="speaker-suggestion-confirm" sx={{ textTransform: "none", fontSize: "0.72rem", py: 0, minHeight: 0 }}>
            {t("speakers.button.confirm")}
          </Button>
        </Box>
      )}

      {confirmed && (
        <Box sx={{ px: 2, pb: 1 }}>
          <Chip label={personName} size="small" data-testid="speaker-person-chip" onDelete={busy ? undefined : onDetach}
            sx={{ height: 24, fontSize: "0.72rem", bgcolor: `${tokens.accent.green}18`, border: `1px solid ${tokens.accent.green}44` }} />
        </Box>
      )}

      <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, px: 1.5, pb: 1, flexWrap: "wrap" }}>
        {cluster.assetUuid && (
          <AuthedAudioPlayer load={playPreview} sourceKey={cluster.uuid} testId="speaker-play"
            label={t("speakers.tooltip.play")} width={200} />
        )}
        <Box sx={{ flex: 1 }} />
        {confirmed ? (
          <Tooltip disableInteractive title={t("speakers.tooltip.saveSample", { name: personName })}>
            <span>
              <Button size="small" startIcon={<SaveAltOutlined sx={{ fontSize: 14 }} />} disabled={busy}
                onClick={() => onSaveSample()} data-testid="speaker-save-sample" sx={{ textTransform: "none", fontSize: "0.72rem" }}>
                {t("speakers.button.saveSample")}
              </Button>
            </span>
          </Tooltip>
        ) : (
          <>
            <Tooltip disableInteractive title={t("speakers.tooltip.assign")}>
              <span>
                <IconButton size="small" disabled={busy} onClick={onAssign} data-testid="speaker-assign" aria-label={t("speakers.tooltip.assign")}>
                  <LinkOutlined sx={{ fontSize: 17 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip disableInteractive title={t("speakers.tooltip.create")}>
              <span>
                <IconButton size="small" disabled={busy} onClick={onCreate} data-testid="speaker-create" aria-label={t("speakers.tooltip.create")}>
                  <PersonAddAlt1Outlined sx={{ fontSize: 17 }} />
                </IconButton>
              </span>
            </Tooltip>
            {reviewStatus !== CLUSTER_STATUS_REJECTED && (
              <Tooltip disableInteractive title={t("speakers.tooltip.reject")}>
                <span>
                  <IconButton size="small" disabled={busy} onClick={onReject} data-testid="speaker-reject" aria-label={t("speakers.tooltip.reject")}>
                    <BlockOutlined sx={{ fontSize: 17 }} />
                  </IconButton>
                </span>
              </Tooltip>
            )}
          </>
        )}
        {/* Non-interactive, like every tooltip on the card: this one opens downwards, straight
            over the first turn's save button, and an interactive popper swallowed the click. */}
        <Tooltip disableInteractive title={expanded ? t("speakers.tooltip.collapse") : t("speakers.tooltip.expand")}>
          <IconButton size="small" onClick={toggleExpanded} data-testid="speaker-expand" aria-expanded={expanded}
            aria-label={expanded ? t("speakers.tooltip.collapse") : t("speakers.tooltip.expand")}>
            {expanded ? <ExpandLessOutlined sx={{ fontSize: 18 }} /> : <ExpandMoreOutlined sx={{ fontSize: 18 }} />}
          </IconButton>
        </Tooltip>
      </Box>

      <Collapse in={expanded} unmountOnExit>
        <Box sx={{ borderTop: `1px solid ${tokens.border.subtle}`, px: 2, py: 1 }} data-testid="speaker-turns">
          {turnsLoading && <CircularProgress size={16} />}
          {!turnsLoading && turns.length === 0 && (
            <Typography variant="caption" color="text.secondary">{t("speakers.label.noTurns")}</Typography>
          )}
          {!turnsLoading && turns.map(turn => {
            // A turn longer than the route allows plays its first two minutes.
            const range = clipRange(turn, AUDIO_CLIP_MAX_MS);
            return (
              <Box key={`${turn.startMs}-${turn.endMs}`} data-testid="speaker-turn" data-start-ms={turn.startMs} data-end-ms={turn.endMs}
                sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.25 }}>
                {cluster.assetUuid && (
                  <AuthedAudioPlayer sourceKey={`${cluster.uuid}:${turn.startMs}`} testId="speaker-turn-play" width={180}
                    label={t("speakers.tooltip.playTurn", { from: formatClock(turn.startMs) })}
                    load={() => fetchAudioClip(token!, cluster.assetUuid!, range.fromMs, range.toMs)} />
                )}
                <Typography variant="caption" sx={{ flex: 1, color: tokens.text.secondary, fontVariantNumeric: "tabular-nums" }}>
                  {t("speakers.label.turn", { from: formatClock(turn.startMs), to: formatClock(turn.endMs), length: formatClock(turn.endMs - turn.startMs) })}
                </Typography>
                {confirmed && (
                  <Tooltip disableInteractive title={t("speakers.tooltip.saveTurn")}>
                    <span>
                      <IconButton size="small" disabled={busy} onClick={() => onSaveSample(range)} data-testid="speaker-turn-save"
                        aria-label={t("speakers.tooltip.saveTurn")}>
                        <SaveAltOutlined sx={{ fontSize: 15 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                )}
              </Box>
            );
          })}
        </Box>
      </Collapse>
    </Paper>
  );
}
