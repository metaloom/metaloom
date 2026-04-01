import * as React from "react";
import Title from "../Demo/Title";
import { Box } from "@mui/system";
import { Typography } from "@mui/material";
import { Link as RouterLink } from "react-router-dom";
import { Link as MaterialLink } from "@mui/material";
import { TextField } from "@mui/material";
import { Button } from "@mui/material";
import Grid from "@mui/material/Grid";
import { Paper } from "@mui/material";

export default function UserNew() {
  return (
    <React.Fragment>
      <Title>Create a new user</Title>
      <Typography variant="subtitle1" component="p">
        A user can be used to authenticate against the API and to login to the
        UI.
      </Typography>
      <Grid container>
        <Grid item xs={12}>
          <Grid container>
            <Grid item xs={6}>
              <Paper
                sx={{
                  p: 2,
                  mb: 2,
                  mt: 2,
                }}
              >
                <Grid container spacing={4}>
                  <Grid item xs={12}>
                    <TextField
                      label="Username*"
                      id="filled-hidden-label-small"
                      defaultValue="Username"
                      variant="filled"
                      size="small"
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <TextField
                      label="Firstname"
                      id="filled-hidden-label-small"
                      defaultValue="Joe"
                      variant="filled"
                      size="small"
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <TextField
                      label="Lastname"
                      id="filled-hidden-label-small"
                      defaultValue="Doe"
                      variant="filled"
                      size="small"
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <TextField
                      label="E-Mail*"
                      id="filled-hidden-label-small"
                      defaultValue="Email"
                      variant="filled"
                      size="small"
                    />
                  </Grid>

                  <Grid item xs={6}>
                    <TextField
                      label="Password*"
                      id="filled-hidden-label-small"
                      defaultValue="Password"
                      variant="filled"
                      size="small"
                    />
                  </Grid>

                  <Grid item xs={6}>
                    <TextField
                      label="Confirm Password"
                      id="filled-hidden-label-small"
                      defaultValue="Password"
                      variant="filled"
                      size="small"
                    />
                  </Grid>
                </Grid>
              </Paper>
            </Grid>
          </Grid>
        </Grid>

        <Grid item xs={12}>
          <Grid item xs={3}>
            <Grid container spacing={4}>
              <Grid item xs={6}>
                <Button variant="contained">Create</Button>
              </Grid>
              <Grid item xs={6}>
                <Button variant="contained" href="/dash/users">
                  Cancel
                </Button>
              </Grid>
            </Grid>
          </Grid>
        </Grid>
      </Grid>
    </React.Fragment>
  );
}
