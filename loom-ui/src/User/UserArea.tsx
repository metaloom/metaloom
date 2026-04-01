import React, { useState, useEffect } from "react";
import Grid from "@mui/material/Grid";
import Paper from "@mui/material/Paper";
import UserListItem, { UserProps, UserListItemProps } from "./UserListItem";
import Container from "@mui/material/Container";
import { styled, alpha, useTheme } from "@mui/material/styles";
import InputBase from "@mui/material/InputBase";
import { padding } from "@mui/system";
import SearchIcon from "@mui/icons-material/Search";
import SendIcon from "@mui/icons-material/Send";
import Button from "@mui/material/Button";
import ButtonGroup from "@mui/material/ButtonGroup";
import ArrowDropDownIcon from "@mui/icons-material/ArrowDropDown";
import ClickAwayListener from "@mui/material/ClickAwayListener";
import Grow from "@mui/material/Grow";
import Popper from "@mui/material/Popper";
import MenuItem from "@mui/material/MenuItem";
import MenuList from "@mui/material/MenuList";
import BreadcrumbArea, { IBreadcrumb } from "../Dashboard/BreadcrumbArea";
import { LastPageRounded } from "@mui/icons-material";

const SearchIconWrapper = styled("div")(({ theme }) => ({
  padding: theme.spacing(0, 2),
  height: "100%",
  position: "absolute",
  pointerEvents: "none",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
}));

const Search = styled("div")(({ theme }) => ({
  position: "relative",
  borderRadius: theme.shape.borderRadius,
  backgroundColor: alpha(theme.palette.common.white, 0.15),
  "&:hover": {
    backgroundColor: alpha(theme.palette.common.white, 0.25),
  },
  marginRight: theme.spacing(2),
  marginLeft: 0,
  width: "100%",
  [theme.breakpoints.up("sm")]: {
    marginLeft: theme.spacing(3),
    width: "auto",
  },
}));

const StyledInputBase = styled(InputBase)(({ theme }) => ({
  color: "inherit",
  "& .MuiInputBase-input": {
    padding: theme.spacing(1, 1, 1, 0),
    // vertical padding + font size from searchIcon
    paddingLeft: `calc(1em + ${theme.spacing(4)})`,
    transition: theme.transitions.create("width"),
    width: "100%",
    [theme.breakpoints.up("md")]: {
      width: "20ch",
    },
  },
}));

function Filter1Button() {
  const options = ["Disabled", "Enabled"];

  const [open, setOpen] = React.useState(false);
  const anchorRef = React.useRef(null);
  const [selectedIndex, setSelectedIndex] = React.useState(1);

  const handleClick = () => {
    console.info(`You clicked ${options[selectedIndex]}`);
  };

  const handleMenuItemClick = (
    event: React.MouseEvent<HTMLElement>,
    index: number
  ) => {
    setSelectedIndex(index);
    setOpen(false);
  };

  const handleToggle = () => {
    setOpen((prevOpen) => !prevOpen);
  };

  const handleClose = (event: TouchEvent | MouseEvent) => {
    /*
    if (anchorRef.current && anchorRef.current.contains(event.target)) {
      return;
    }*/

    setOpen(false);
  };

  return (
    <React.Fragment>
      <ButtonGroup
        variant="contained"
        ref={anchorRef}
        aria-label="split button"
      >
        <Button onClick={handleClick}>{options[selectedIndex]}</Button>
        <Button
          size="small"
          aria-controls={open ? "split-button-menu" : undefined}
          aria-expanded={open ? "true" : undefined}
          aria-label="select merge strategy"
          aria-haspopup="menu"
          onClick={handleToggle}
        >
          <ArrowDropDownIcon />
        </Button>
      </ButtonGroup>
      <Popper
        open={open}
        anchorEl={anchorRef.current}
        role={undefined}
        transition
        disablePortal
      >
        {({ TransitionProps, placement }) => (
          <Grow
            {...TransitionProps}
            style={{
              transformOrigin:
                placement === "bottom" ? "center top" : "center bottom",
            }}
          >
            <Paper>
              <ClickAwayListener onClickAway={handleClose}>
                <MenuList id="split-button-menu">
                  {options.map((option, index) => (
                    <MenuItem
                      key={option}
                      disabled={index === 2}
                      selected={index === selectedIndex}
                      onClick={(event) => handleMenuItemClick(event, index)}
                    >
                      {option}
                    </MenuItem>
                  ))}
                </MenuList>
              </ClickAwayListener>
            </Paper>
          </Grow>
        )}
      </Popper>
    </React.Fragment>
  );
}

function MainSearchBar() {
  return (
    <Search>
      <SearchIconWrapper>
        <SearchIcon />
      </SearchIconWrapper>
      <StyledInputBase
        placeholder="Search…"
        inputProps={{ "aria-label": "search" }}
      />
    </Search>
  );
}

function HeaderArea() {
  const theme = useTheme();

  return (
    <Grid
      item
      xs={12}
      sx={{
        padding: 3,
        paddingRight: 5,
        ml: 4,
        borderRadius: "20px",
        backgroundColor: theme.palette.primary.main,
      }}
    >
      <Grid container spacing={3}>
        <Grid item xs={6}>
          <MainSearchBar />
        </Grid>
        <Grid item xs={3}>
          <Filter1Button />
        </Grid>
        <Grid item xs={1}>
          Filter2
        </Grid>
        <Grid item xs={1}>
          Filter3
        </Grid>
        <Grid item xs={1}>
          <Button variant="contained" href="users/new" endIcon={<SendIcon />}>
            New
          </Button>
        </Grid>
      </Grid>
    </Grid>
  );
}

type UserAreaProps = {
  setBreadcrumb: Function;
};

export default function UserArea({ setBreadcrumb }: UserAreaProps) {
  useEffect(() => {
    setBreadcrumb([
      { key: "Dash", path: "/dash" },
      { key: "Users", path: "/users" },
    ]);
  }, []);

  let user: UserProps = {
    username: "joeDoe",
    email: "git@jotschi.de",
    firstname: "Joe",
    lastname: "Doe",
    password: "joedoe",
  };

  return (
    <Grid container spacing={3}>
      <HeaderArea />
      {[...Array(36)].map((x, i) => (
        <UserListItem key={i} user={user} id="{i}" />
      ))}
    </Grid>
  );
}
