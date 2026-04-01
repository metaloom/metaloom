import React,{useEffect} from "react";
import Grid from "@mui/material/Grid";
import Paper from "@mui/material/Paper";
import AssetList from "../Asset/AssetList";
import Container from "@mui/material/Container";
import AssetMetadataDrawer from "./AssetMetadataDrawer";

type AssetAreaProps = {
  setBreadcrumb: Function;
};

export default function AssetArea({setBreadcrumb}: AssetAreaProps) {

  useEffect(() => {
    setBreadcrumb([{ key: "Dash", path: "/dash" }, { key: "Assets", path: "/assets" }]);
  }, []);

  return (
    <Grid container spacing={3}>
      {[...Array(36)].map((x, i) => (
        <Grid item xs={3}>
          <Paper
            sx={{
              p: 2,
              height: 200,
            }}
          >
            <AssetList />
          </Paper>
        </Grid>
      ))}
    </Grid>
  );

  //  <AssetMetadataDrawer/>
}
