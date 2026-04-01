import * as React from "react";
import { Routes, Route } from "react-router-dom";
import CssBaseline from "@mui/material/CssBaseline";
import Box from "@mui/material/Box";
import Toolbar from "@mui/material/Toolbar";
import Breadcrumbs from "@mui/material/Breadcrumbs";
import { IBreadcrumb } from "./BreadcrumbArea";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import Grid from "@mui/material/Grid";
import Container from "@mui/material/Container";

import DashboardContentBox from "../Content/ContentArea";
import TopBar from "./TopBar";
import ContentArea from "../Content/ContentArea";
import AssetArea from "../Asset/AssetArea";
import UserArea from "../User/UserArea";
import UserNew from "../User/UserNew";
import UserEdit from "../User/UserEdit";
import PipelineArea from "../Pipeline/PipelineArea";
import { styled, alpha, useTheme } from "@mui/material/styles";

function Copyright(props: any) {
  return (
    <Typography
      variant="body2"
      color="text.secondary"
      align="center"
      {...props}
    >
      {"Copyright © "}
      <Link color="inherit" href="https://metaloom.io/">
        MetaLoom
      </Link>{" "}
      {new Date().getFullYear()}
      {"."}
    </Typography>
  );
}

function DashboardContent() {
  const [open, setOpen] = React.useState(true);
  const [breadcrumb, setBreadcrumb] = React.useState<IBreadcrumb[] | []>([]);

  const theme = useTheme();

  return (
    <Grid container sx={{ display: "flex" }}>
      <CssBaseline />

      <TopBar open={open} setOpen={setOpen} breadcrumb={breadcrumb} setBreadcrumb={setBreadcrumb} />
      <Container
        maxWidth="lg"
        sx={{
          mt: 20,
          mb: 4,
          ml: 30,
          transition: theme.transitions.create(["width", "margin"], {
            easing: theme.transitions.easing.sharp,
            duration: theme.transitions.duration.leavingScreen,
          }),

          ...(!open && {
            transition: theme.transitions.create(["width", "margin"], {
              easing: theme.transitions.easing.sharp,
              duration: theme.transitions.duration.enteringScreen,
            }),
            ml: 15,
          }),
        }}
      >
        <Grid item xs={12}>
          <Routes>
            <Route path="contents" element={<ContentArea setBreadcrumb={setBreadcrumb} />} />
            <Route path="content2" element={<DashboardContentBox setBreadcrumb={setBreadcrumb} />} />
            <Route path="content3" element={<DashboardContentBox setBreadcrumb={setBreadcrumb} />} />
            <Route path="assets" element={<AssetArea setBreadcrumb={setBreadcrumb}  />} />
            <Route path="users" element={<UserArea setBreadcrumb={setBreadcrumb} />} />
            <Route path="users/:id" element={<UserEdit />} />
            <Route path="users/new" element={<UserNew />} />
            <Route path="pipelines" element={<PipelineArea />} />
          </Routes>
        </Grid>
        <Copyright sx={{ pt: 4 }} />
      </Container>
    </Grid>
  );
}

export default function Dashboard() {
  return <DashboardContent />;
}
