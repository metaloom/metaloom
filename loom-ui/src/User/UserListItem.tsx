import * as React from "react";
import Title from "../Demo/Title";
import Grid from "@mui/material/Grid";
import Paper from "@mui/material/Paper";
import { Link as RouterLink } from "react-router-dom";
import Gravatar from "react-gravatar";
import { Link as MaterialLink } from "@mui/material";
import { Link } from "react-router-dom";
import { Button } from "@mui/material";
import { ButtonGroup } from "@mui/material";
import { Popper } from "@mui/material";
import { Grow } from "@mui/material";
import ArrowDropDownIcon from "@mui/icons-material/ArrowDropDown";
import ClickAwayListener from "@mui/material/ClickAwayListener";
import MenuList from "@mui/material/MenuList";
import MenuItem from "@mui/material/MenuItem";

export type UserProps = {
  username: string;
  email: string;
  password: string;
  firstname: string;
  lastname: string;
};

export type UserListItemProps = {
  id: string;
  user: UserProps;
};

const options = ["Disable", "Enable", "Delete"];

function ActionButton() {
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
    }
    */

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

export default function UserListItem({ id, user }: UserListItemProps) {
  return (
    <Grid item xs={12}>
      <Paper
        sx={{
          p: 2,
          height: 100,
        }}
      >
        <Grid container spacing={0}>
          <Grid item xs={1}>
            <Gravatar alt={user.email} email={user.email} size={50} />
          </Grid>
          <Grid item xs={1}>
            <Link to={id}>{user.username}</Link>
          </Grid>
          <Grid item xs={2}>
            <Title>
              {user.firstname} {user.lastname}
            </Title>
          </Grid>
          <Grid item xs={2}>
            <Title>{user.email}</Title>
          </Grid>
          <Grid item xs={2}>
            <Title>Status</Title>
          </Grid>
          <Grid item xs={2}>
            <MaterialLink component={RouterLink} to="/dash/users">
              <Title>ACL</Title>
            </MaterialLink>
          </Grid>
          <Grid item xs={2}>
            <ActionButton />
          </Grid>
        </Grid>
      </Paper>
    </Grid>
  );
}
