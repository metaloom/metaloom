import * as React from "react";
import { Navigate } from "react-router-dom";

/**
 * Legacy AssetList placeholder — redirects to the main asset browser view.
 */
export default function AssetList() {
  return <Navigate to="/assets" replace />;
}
