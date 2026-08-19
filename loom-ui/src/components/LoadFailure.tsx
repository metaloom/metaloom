import React from "react";
import { Alert, AlertTitle, Box, Button } from "@mui/material";

/**
 * The inline "this could not be loaded" state.
 *
 * **Why this is not a toast.** A failed load has to keep saying so. A toast fades after a few
 * seconds and leaves a screen that reads as *empty* - and "there are no libraries" is a
 * completely different, far more alarming statement than "the libraries could not be loaded".
 * Twelve views in this tree turned a 500 into an empty state exactly that way. So: a mutation
 * toasts, a load renders one of these, and a load that also toasts is doing both.
 *
 * Pairs with `EmptyState`, which is for the other case - the request succeeded and there is
 * genuinely nothing there. Reaching for the wrong one of the two is the bug this exists to make
 * hard to write.
 */
export default function LoadFailure({
  message,
  onRetry,
  testId = "load-failure",
}: {
  /** The human sentence from the failure. Never a stack trace. */
  message: string;
  /** Offered when the caller can re-run the load. Omitted rather than faked when it cannot. */
  onRetry?: () => void;
  testId?: string;
}) {
  return (
    <Box sx={{ p: 3 }} data-testid={testId}>
      <Alert
        severity="error"
        action={
          onRetry ? (
            <Button color="inherit" size="small" onClick={onRetry} data-testid={`${testId}-retry`}>
              Try again
            </Button>
          ) : undefined
        }
      >
        <AlertTitle>Could not load</AlertTitle>
        {message}
      </Alert>
    </Box>
  );
}
