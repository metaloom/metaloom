import * as React from "react";
import Paper from "@mui/material/Paper";
import Chart from "../Demo/Chart";
import Deposits from "../Demo/Deposits";
import Orders from "../Demo/Orders";
import Assets from "../Asset/AssetList";
import { Grid, Container, Link } from '@mui/material';
import { styled } from "@mui/system";

import Typography from "@mui/material/Typography";
import "./flow-style.css";
import "react-flow-renderer/dist/theme-default.css";

import ReactFlow, {
  ReactFlowProvider,
  addEdge,
  removeElements,
  Controls,
} from "react-flow-renderer";

const elements = [
  { id: "src", data: { label: "Source" }, position: { x: 250, y: 5 } },
  { id: "fp", data: { label: <div>Fingerprint</div> }, position: { x: 100, y: 100 } },
  { id: "hash", data: { label: <div>Hash</div> }, position: { x: 100, y: 100 } },
  { id: "resize", data: { label: <div>Resize</div> }, position: { x: 100, y: 100 } },
  { id: "filter", data: { label: <div>Filter</div> }, position: { x: 100, y: 100 } },
  { id: "s3", data: { label: <div>S3</div> }, position: { x: 100, y: 100 } },

  { id: "e1", source: "src", target: "filter", animated: true },
  { id: "e2", source: "filter", target: "hash", animated: true },
  { id: "e3", source: "filter", target: "resize", animated: true },
  { id: "e4", source: "filter", target: "fp", animated: true },
  { id: "e5", source: "resize", target: "s3", animated: true },
];

const BasicFlow = () => <ReactFlow style={{height: 700, width: 1500}} elements={elements} />;

const StyledFlow = styled(BasicFlow)(
  ({ theme }) => `
`
);


export default function PipelineArea() {
  return (
    <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
      <Grid item xs={12} sm={6} md={3}>
        <ReactFlowProvider>
          <StyledFlow />
        </ReactFlowProvider>
      </Grid>
    </Container>
  );
}
