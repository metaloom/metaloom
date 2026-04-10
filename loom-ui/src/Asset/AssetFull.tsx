import * as React from "react";
import { Navigate, useParams } from "react-router-dom";

/**
 * Legacy AssetFull placeholder — redirects to the main asset detail view.
 */
export default function AssetFull() {
  const { id } = useParams<{ id?: string }>();
  if (id) {
    return <Navigate to={`/assets/${id}`} replace />;
  }
  return <Navigate to="/assets" replace />;
}