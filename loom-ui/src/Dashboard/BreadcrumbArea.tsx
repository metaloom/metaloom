import React, { useState, useEffect, useCallback } from "react";
import Breadcrumbs from "@mui/material/Breadcrumbs";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import Grid from "@mui/material/Grid";

export interface IBreadcrumb {
  key: string;
  path: string;
}

export type BreadcrumbAreaProps = {
  breadcrumb: IBreadcrumb[] | [];
  setBreadcrumb: Function;
};

export default function BreadcrumbArea({
  breadcrumb,
  setBreadcrumb,
}: BreadcrumbAreaProps) {
  const handleInputChange = useCallback(
    (event) => {
      setBreadcrumb(event.target.value);
    },
    [setBreadcrumb]
  );

  console.log("Re-render");
  var entries = [];
  if (breadcrumb.length > 0) {
    if (breadcrumb.length >= 1) {
      for (var i = 0; i < breadcrumb.length - 1; i++) {
        entries.push(
          <Link
            key={i}
            underline="hover"
            color="inherit"
            href={breadcrumb[i].path}
          >
            {breadcrumb[i].key}
          </Link>
        );
      }
    }

    entries.push(
      <Typography key={breadcrumb.length} color="text.primary">
        {breadcrumb[breadcrumb.length - 1].key}
      </Typography>
    );
  }

  return (
    <Grid
      item
      xs={12}
      sx={{
        padding: "8px",
      }}
    >
      <Breadcrumbs aria-label="breadcrumb">{entries}</Breadcrumbs>
    </Grid>
  );

  /*
    return (
      <Grid
        item
        xs={12}
        sx={{
          padding: "8px",
        }}
      >
        <Breadcrumbs aria-label="breadcrumb">
          <Link underline="hover" color="inherit" href="/">
            {breadcrumb[0].name}
          </Link>
          <Link
            underline="hover"
            color="inherit"
            href="/getting-started/installation/"
          >
            {breadcrumb[0].name}
          </Link>
          <Typography color="text.primary">{breadcrumb[0].name}</Typography>
        </Breadcrumbs>
      </Grid>
    );
    */
}
