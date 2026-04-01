import * as React from "react";
import Title from "../Demo/Title";
import { Link as RouterLink } from "react-router-dom";
import { Link as MaterialLink } from "@mui/material";
import TreeDrawer from "./Tree/TreeDrawer";

export default function AssetList() {
  return (
    <React.Fragment>
      <Title>Asset List</Title>
      <MaterialLink component={RouterLink} to="/dash/contents">Contents</MaterialLink>
    </React.Fragment>
  );
  //<TreeDrawer /> 
}
