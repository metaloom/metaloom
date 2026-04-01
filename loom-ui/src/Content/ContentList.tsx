import * as React from "react";
import Title from "../Demo/Title";
import { Link as RouterLink } from "react-router-dom";
import { Link as MaterialLink } from "@mui/material";

export default function ContentList() {
  return (
    <React.Fragment>
      <Title>Content List</Title>
      <MaterialLink component={RouterLink} to="/dash/assets">Assets</MaterialLink>
    </React.Fragment>
  );
}
