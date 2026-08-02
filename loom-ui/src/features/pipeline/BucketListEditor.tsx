import AddIcon from "@mui/icons-material/Add";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import { Box, Button, IconButton, Stack, TextField, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import { FILTER_RESERVED_BUCKET_IDS } from "./portResolvers";

/** One row, as the pipeline definition stores it. */
export interface Bucket {
  id: string;
  label?: string;
  match?: string;
}

interface BucketListEditorProps {
  value: Bucket[];
  onChange: (next: Bucket[]) => void;
}

/** Mirrors `PortSpec.ID_PATTERN`; a row failing it resolves to no port. */
const PORT_ID_PATTERN = /^[a-z0-9][a-z0-9_]{0,62}$/;

/**
 * Turn what someone typed into something that can legally be a port id, rather than rejecting it.
 * "Brazilian Portuguese" becoming `brazilian_portuguese` is far friendlier than an error telling
 * them which characters a port id may contain.
 */
export function slugifyBucketId(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, "_")
    .replace(/^_+/, "")
    .slice(0, 63);
}

/** The reason this row will not produce a port, or null when it is fine. */
export function bucketIdError(id: string, index: number, all: Bucket[]): string | null {
  if (!id) return "required";
  if (FILTER_RESERVED_BUCKET_IDS.has(id)) return "reserved";
  if (!PORT_ID_PATTERN.test(id)) return "invalid";
  if (all.some((b, i) => i !== index && b.id === id)) return "duplicate";
  return null;
}

/**
 * Repeatable row editor for a `PORT_LIST` parameter: each row's `id` becomes one output port on the
 * node, so adding a row grows the node a handle and removing one takes its connections with it.
 *
 * This deliberately does **not** round-trip through JSON text. The `JSON` parameter editor commits
 * its raw string on every keystroke, which means a half-typed value parses to nothing and every
 * derived handle on the node vanishes until it is valid again. A parameter that defines ports cannot
 * behave that way, so `onChange` always emits a structurally valid array and an unfinished row
 * simply resolves to no port without disturbing its neighbours.
 */
export default function BucketListEditor({ value, onChange }: BucketListEditorProps) {
  const { t } = useTranslation();

  const update = (index: number, patch: Partial<Bucket>) => {
    onChange(value.map((bucket, i) => (i === index ? { ...bucket, ...patch } : bucket)));
  };

  const remove = (index: number) => {
    onChange(value.filter((_, i) => i !== index));
  };

  const add = () => {
    onChange([...value, { id: "", label: "" }]);
  };

  return (
    <Box data-testid="bucket-list-editor">
      <Stack spacing={1}>
        {value.map((bucket, index) => {
          const error = bucketIdError(bucket.id, index, value);
          return (
            <Stack
              key={index}
              direction="row"
              spacing={0.5}
              alignItems="flex-start"
              data-testid={`bucket-row-${index}`}
            >
              <TextField
                size="small"
                label={t("pipeline.buckets.id", "Id")}
                value={bucket.id}
                error={!!error}
                helperText={error ? t(`pipeline.buckets.error.${error}`, error) : " "}
                inputProps={{ "data-testid": `bucket-id-${index}` }}
                onChange={(e) => update(index, { id: slugifyBucketId(e.target.value) })}
                sx={{ flex: "0 0 30%" }}
              />
              <TextField
                size="small"
                label={t("pipeline.buckets.label", "Label")}
                value={bucket.label ?? ""}
                helperText=" "
                inputProps={{ "data-testid": `bucket-label-${index}` }}
                onChange={(e) => update(index, { label: e.target.value })}
                sx={{ flex: "0 0 30%" }}
              />
              <TextField
                size="small"
                label={t("pipeline.buckets.match", "Aliases")}
                placeholder="german, deutsch"
                value={bucket.match ?? ""}
                helperText=" "
                inputProps={{ "data-testid": `bucket-match-${index}` }}
                onChange={(e) => update(index, { match: e.target.value })}
                sx={{ flex: 1 }}
              />
              <IconButton
                size="small"
                aria-label={t("pipeline.buckets.remove", "Remove bucket")}
                data-testid={`bucket-remove-${index}`}
                onClick={() => remove(index)}
                sx={{ mt: 0.5 }}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </Stack>
          );
        })}
      </Stack>

      <Button size="small" variant="text" startIcon={<AddIcon />} data-testid="bucket-add" onClick={add}>
        {t("pipeline.buckets.add", "Add bucket")}
      </Button>

      <Typography variant="caption" color="text.secondary" display="block">
        {t(
          "pipeline.buckets.hint",
          "Each bucket becomes an output port. An 'other' port for everything else is always present.",
        )}
      </Typography>
    </Box>
  );
}
