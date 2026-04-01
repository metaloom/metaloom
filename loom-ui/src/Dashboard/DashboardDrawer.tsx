import * as React from "react";
import Divider from "@mui/material/Divider";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import { styled } from "@mui/material/styles";
import List from "@mui/material/List";
import MuiDrawer from "@mui/material/Drawer";
import Toolbar from "@mui/material/Toolbar";
import IconButton from "@mui/material/IconButton";
import ListItem from "@mui/material/ListItem";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import ListSubheader from "@mui/material/ListSubheader";
import DashboardIcon from "@mui/icons-material/Dashboard";
import FileIcon from "@mui/icons-material/FileDownload";
import ShoppingCartIcon from "@mui/icons-material/ShoppingCart";
import PeopleIcon from "@mui/icons-material/People";
import Grid from "@mui/material/Grid";
import Box from "@mui/material/Box";
import MenuIcon from "@mui/icons-material/Menu";
import BarChartIcon from "@mui/icons-material/BarChart";
import LayersIcon from "@mui/icons-material/Layers";
import AssignmentIcon from "@mui/icons-material/Assignment";
import Typography from "@mui/material/Typography";
import { ReactComponent as LoomLogo } from "../img/logo_picto.svg";
import { Link } from "react-router-dom";
import { SvgIcon } from "@mui/material";

const mainListItems = (
  <div>
    <Entry name="Assets" path="assets" />
    <Entry name="Contents" path="contents" />
    <Entry name="Namespaces" path="namespaces" />
    <Entry name="Models" path="models" />
  </div>
);

interface EntryProps {
  name?: string;
  path: string;
}

function Entry(props: EntryProps) {
  return (
    <ListItem button component={Link} to={props.path}>
      <ListItemIcon>
        <AssignmentIcon />
      </ListItemIcon>
      <ListItemText primary={props.name} />
    </ListItem>
  );
}

export const adminListItems = (
  <div>
    <ListSubheader inset>Admin</ListSubheader>
    <Entry name="Extensions" path="extensions" />
    <Entry name="Pipelines" path="pipelines" />
    <Entry name="API Keys" path="auth" />
  </div>
);

export const aclListItems = (
  <div>
    <ListSubheader inset>ACL</ListSubheader>
    <Entry name="Users" path="users" />
    <Entry name="Groups" path="groups" />
    <Entry name="Roles" path="roles" />
  </div>
);

const Drawer = styled(MuiDrawer, {
  shouldForwardProp: (prop) => prop !== "open",
})(({ theme, open }) => ({
  "& .MuiDrawer-paper": {
    position: "relative",
    whiteSpace: "nowrap",
    width: drawerWidth(),
    transition: theme.transitions.create("width", {
      easing: theme.transitions.easing.sharp,
      duration: theme.transitions.duration.enteringScreen,
    }),
    boxSizing: "border-box",
    ...(!open && {
      overflowX: "hidden",
      transition: theme.transitions.create("width", {
        easing: theme.transitions.easing.sharp,
        duration: theme.transitions.duration.leavingScreen,
      }),
      width: theme.spacing(7),
      [theme.breakpoints.up("sm")]: {
        width: theme.spacing(9),
      },
    }),
  },
}));

export function drawerWidth() {
  return 240;
}

type DashboardDrawerLeftProps = {
  open: boolean;
  setOpen: Function;
};

export default function DashboardDrawer({
  open,
  setOpen,
}: DashboardDrawerLeftProps) {
  const toggleDrawer = () => {
    setOpen(!open);
  };
  return (
    <Drawer
      variant="permanent"
      sx={{
        position: "absolute",
        top: "0px",
        left: "0px",
      }}
      open={open}
    >
      <Toolbar
        sx={{
          display: "flex",
          alignItems: "center",
          justifyContent: "flex-end",
          height: "130px",
          px: [1],
        }}
      >
        <Grid container>
          <Grid item xs={12}>
            <IconButton
              size="large"
              color="inherit"
              aria-label="open drawer"
              onClick={toggleDrawer}
              sx={{
                "&:hover": { color: "black" },
                ...(open && { transform: "rotate(90deg)" }),
              }}
            >
              <MenuIcon />
            </IconButton>
            <SvgIcon>
              <LoomLogo />
            </SvgIcon>
            <Box
              component="img"
              sx={{
                width: 100,
                maxHeight: { xs: 233, md: 167 },
                maxWidth: { xs: 350, md: 250 },
              }}
              alt="Logo"
              src="/img/logo_picto.png"
            />
          </Grid>
        </Grid>
      </Toolbar>
      <Divider />
      <List>{mainListItems}</List>
      <Divider />
      <List>{aclListItems}</List>
      <Divider />
      <List>{adminListItems}</List>
    </Drawer>
  );
}
