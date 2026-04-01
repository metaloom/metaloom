import * as React from "react";
import Typography from "@mui/material/Typography";
import { Container, Icon, Box, SvgIcon } from "@mui/material";
import { styled } from "@mui/system";
import { makeStyles } from "@mui/styles";

import { ReactComponent as LoomSVG } from "../img/logo_word_min.svg";
import  LoomImg from "../img/logo_word_3d.png";


function preventDefault(event: React.MouseEvent) {
  event.preventDefault();
}

const FullScreen = styled("div")(
  ({ theme }) => `
  position: absolute;
  top: 0;
  bottom: 0;
  right: 0;
  left: 0;
  overflow: hidden;
`
);

const LoomLogo = styled(Icon)(
  ({ theme }) => `
  background: red;
  width: 10px;
`
);

const BluredArea = styled("div")(
  ({ theme }) => `
  filter: blur(10px);
  background: rgb(35,43,43);
  background: linear-gradient(101deg, rgba(35,43,43,1) 0%, rgba(147,180,177,1) 48%, rgba(36,45,45,1) 100%);
  position: absolute;
  top: -100px;
  bottom: -100px;
  right: -100px;
  left: -100px;
  //border: solid 2px black;
  overflow: hidden;
`
);

const BluredArea2 = styled("div")(
  ({ theme }) => `
  filter: blur(10px);
  opacity: 0.5;
  background: rgb(35,43,43);
  background: linear-gradient(139deg, rgba(35,43,43,1) 0%, rgba(147,180,177,1) 48%, rgba(36,45,45,1) 100%);
  position: absolute;
  top: -100px;
  bottom: -100px;
  right: -100px;
  left: -100px;
  //border: solid 2px black;
  overflow: hidden;
`
);

const LogoArea = styled("div")(
  ({ theme }) => `
  display: table;
  margin: 0 auto;
  border: 1px solid black;
  width: 300px;
  /*
  position: absolute;
  top: 0;
  bottom: 0;
  right: 0;
  left: 0;
  filter: blur(0px);
  height: "100%";
  */
`
);

const LoomLogoComponent = styled(LoomSVG)(
  ({ theme }) => `
  margin: 0;
  position: absolute;
  top: 50%;
  -ms-transform: translateY(-50%);
  transform: translateY(-50%);
  filter: saturate(0%) brightness(298%)drop-shadow(-2px 0px 6px #666666);
  
  width: 600px;
  height: 200px;
  z-index: 99999999;
  `
);

const LogoImgComponent = () => {
  return <img src={LoomImg} alt="Logo" />;
}

const LoomLogoImageComponent = styled(LogoImgComponent)(
  ({ theme }) => `
  margin: 0;
  position: absolute;
  top: 50%;
  width: 600px;
  height: 200px;
  z-index: 99999999;
  `
);

export default function WelcomePage() {
  return (
    <React.Fragment>
      <FullScreen>
        <BluredArea>
          <BluredArea2 />
        </BluredArea>
        <LogoArea>
          <LoomLogoImageComponent />
        </LogoArea>
      </FullScreen>
    </React.Fragment>
  );
  /*
      <Container sx={{
            backgroundColor: "yellow",
            width: "100%",
            height: "100%",
            maxWidth: "900px",
            boxShadow: 0,
          }}
          maxWidth="sm">
      </Container>
        <Typography component="p" variant="h4">
          Welcome to Loom
        </Typography>
  */
}
