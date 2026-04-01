import React, { useEffect} from "react";
import Grid from "@mui/material/Grid";
import Paper from "@mui/material/Paper";
import Chart from "../Demo/Chart";
import Deposits from "../Demo/Deposits";
import Orders from "../Demo/Orders";
import Assets from "../Asset/AssetList";
import Container from "@mui/material/Container";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import ContentList from "./ContentList";

type UserAreaProps = {
  setBreadcrumb: Function;
};

export default function ContentArea({setBreadcrumb}: UserAreaProps) {

  useEffect(() => {
    setBreadcrumb([{ key: "Dash", path: "/dash" }, { key: "Contents", path: "/contents" }]);
  }, []);

  return (
    <Grid container spacing={3}>
      {/* Assets */}
      <Grid item xs={12} md={8} lg={9}>
        <Paper
          sx={{
            p: 2,
            display: "flex",
            flexDirection: "column",
            height: 240,
          }}
        >
          <ContentList />
        </Paper>
      </Grid>
      {/* Chart */}
      <Grid item xs={12} md={8} lg={9}>
        <Paper
          sx={{
            p: 2,
            display: "flex",
            flexDirection: "column",
            height: 240,
          }}
        >
          <Chart />
        </Paper>
      </Grid>
      {/* Recent Deposits */}
      <Grid item xs={12} md={4} lg={3}>
        <Paper
          sx={{
            p: 2,
            display: "flex",
            flexDirection: "column",
            height: 240,
          }}
        >
          <Deposits />
        </Paper>
      </Grid>
      {/* Recent Orders */}
      <Grid item xs={12}>
        <Paper sx={{ p: 2, display: "flex", flexDirection: "column" }}>
          <Orders />
        </Paper>
      </Grid>
    </Grid>
  );
}
