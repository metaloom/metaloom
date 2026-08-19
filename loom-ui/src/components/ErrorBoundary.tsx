import React from "react";
import { Alert, AlertTitle, Box, Button, Stack } from "@mui/material";

/**
 * Contains a render throw to one screen.
 *
 * Without one, a throw anywhere in the tree unmounts the whole app: React 18 discards the entire
 * root, the user gets a white page, and - because auth is held in memory - the reload they try
 * next dumps them at the login form having lost their work. One boundary per route turns that
 * into a failed panel with the sidebar still working.
 *
 * **Reset, not reload.** The fallback's action clears the error and re-renders the subtree.
 * `location.reload()` would be the easy implementation and the wrong one, for the same reason:
 * it throws away the in-memory session.
 */

interface Props {
  children: React.ReactNode;
  /** Named in the fallback, so the user can say which screen broke. */
  feature: string;
  /**
   * Called with the error, so the shell can offer to report it.
   *
   * Optional: the boundary must still work when rendered without a failure surface above it,
   * because a boundary that needs a context to not crash is a boundary that can crash.
   */
  onError?: (error: Error, feature: string) => void;
}

interface State {
  error: Error | null;
}

export default class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    // Kept: the component stack is the only place the failing component is named, and it does not
    // survive into the state. This is a console.error that is NOT a swallowed failure - the user
    // is being shown the fallback below, and offered a report through onError.
    console.error(`Render failure in ${this.props.feature}`, error, info.componentStack);
    this.props.onError?.(error, this.props.feature);
  }

  private reset = () => {
    this.setState({ error: null });
  };

  render(): React.ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <Box sx={{ p: 3 }} data-testid="error-boundary-fallback">
        <Alert
          severity="error"
          action={
            <Stack direction="row" spacing={1}>
              <Button color="inherit" size="small" onClick={this.reset} data-testid="error-boundary-retry">
                Reload this view
              </Button>
            </Stack>
          }
        >
          <AlertTitle>This screen could not be displayed</AlertTitle>
          {this.props.feature} failed to render: {error.message || "an unexpected error occurred"}.
          The rest of the application is unaffected — the navigation on the left still works.
        </Alert>
      </Box>
    );
  }
}
