/**
 * Capturing what was on the user's screen, for a problem report.
 *
 * **Why `getDisplayMedia` and not a DOM-to-canvas renderer.** The alternative - html2canvas and
 * friends - re-renders the DOM into a canvas from the computed styles. It is silent and needs no
 * permission, but it does not capture what the user actually saw: `<video>` frames come out
 * blank, cross-origin asset previews come out blank, and a wrongly-rendered element is
 * re-rendered by the same code that rendered it wrongly, so the screenshot agrees with the bug
 * instead of showing it. For a bug report those are exactly the pixels that matter. This captures
 * the real compositor output.
 *
 * **What it costs.** The browser shows a picker every time, and the user chooses what to share.
 * That is a feature here rather than a tax: nothing is captured without an explicit, per-capture
 * choice, and a user who does not want to share their screen simply does not press the button.
 * It also means they may pick the wrong surface, which is why {@link captureScreenshot} hands the
 * image back for the user to look at before anything is submitted.
 *
 * **Where it does not work.** `getDisplayMedia` needs a secure context - HTTPS, or localhost.
 * On a plain-HTTP deployment `navigator.mediaDevices` is undefined, and
 * {@link isScreenshotSupported} answers false so the dialog can hide the button rather than
 * offering one that throws.
 */

/** The image type submitted. PNG because a screenshot of a UI is flat colour and text. */
const IMAGE_TYPE = "image/png";

/**
 * Longest edge of the submitted image.
 *
 * A 4K screen encodes to several megabytes of PNG, over the server's 5 MB cap. Downscaling to
 * 1920 keeps text legible while landing comfortably under it. Smaller screens are never scaled
 * up - blowing up a 1280px capture would only make it blurry.
 */
const MAX_EDGE = 1920;

export interface Screenshot {
  /** A `data:image/png;base64,...` URL. Rendered directly into an `<img>` and sent as-is. */
  dataUrl: string;
  width: number;
  height: number;
}

/**
 * Whether this browser and this deployment can capture a screenshot at all.
 *
 * False on plain HTTP (no secure context), in older browsers, and anywhere `mediaDevices` is
 * blocked by permissions policy. Callers use it to decide whether to render the button, so that a
 * user is never offered a control that can only fail.
 */
export function isScreenshotSupported(): boolean {
  return (
    typeof navigator !== "undefined" &&
    typeof navigator.mediaDevices !== "undefined" &&
    typeof navigator.mediaDevices.getDisplayMedia === "function"
  );
}

/**
 * Ask the user to pick a surface, grab one frame from it, and return it as a PNG data URL.
 *
 * Resolves with null when the user dismisses the picker - a cancelled capture is a choice, not an
 * error, and must not put a red toast in front of somebody who is already reporting a bug.
 * Rejects only when the capture itself fails.
 */
export async function captureScreenshot(): Promise<Screenshot | null> {
  if (!isScreenshotSupported()) {
    throw new Error("Screen capture is not available in this browser.");
  }

  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({
      // `preferCurrentTab` is Chromium-only and merely reorders the picker; other browsers ignore
      // the unknown key. It does not remove the user's choice, it just puts the likely one first.
      video: { displaySurface: "browser" },
      audio: false,
      preferCurrentTab: true,
    } as DisplayMediaStreamOptions);
  } catch (e) {
    // NotAllowedError is what a dismissed picker throws. It is indistinguishable from a policy
    // denial, and treating both as "the user did not want to" is the reading that never nags.
    if (e instanceof DOMException && (e.name === "NotAllowedError" || e.name === "AbortError")) {
      return null;
    }
    throw e;
  }

  try {
    return await grabFrame(stream);
  } finally {
    // Always, on every path. A live capture track leaves the browser's "sharing your screen"
    // indicator up, which is alarming and would outlive the dialog that started it.
    stream.getTracks().forEach((track) => track.stop());
  }
}

async function grabFrame(stream: MediaStream): Promise<Screenshot> {
  const video = document.createElement("video");
  video.srcObject = stream;
  video.muted = true;
  // Off-screen rather than hidden: `display:none` stops some browsers decoding frames at all,
  // so the video would never reach readyState and this would hang.
  video.style.position = "fixed";
  video.style.opacity = "0";
  video.style.pointerEvents = "none";
  video.style.left = "-10000px";
  document.body.appendChild(video);

  try {
    await video.play();
    await firstFrame(video);

    const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
    const width = Math.max(1, Math.round(video.videoWidth * scale));
    const height = Math.max(1, Math.round(video.videoHeight * scale));

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      throw new Error("Could not capture the screen: no 2D canvas context.");
    }
    ctx.drawImage(video, 0, 0, width, height);

    return { dataUrl: canvas.toDataURL(IMAGE_TYPE), width, height };
  } finally {
    video.srcObject = null;
    video.remove();
  }
}

/**
 * Wait until the element has a decoded frame with real dimensions.
 *
 * `play()` resolving is not enough: the first frame may not have arrived, and drawing then yields
 * a 0x0 or transparent image. The timeout keeps a stalled track from hanging the dialog forever.
 */
function firstFrame(video: HTMLVideoElement): Promise<void> {
  if (video.readyState >= 2 && video.videoWidth > 0) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      cleanup();
      reject(new Error("Timed out waiting for the screen capture to produce a frame."));
    }, 5000);

    const onReady = () => {
      if (video.videoWidth > 0) {
        cleanup();
        resolve();
      }
    };
    const cleanup = () => {
      window.clearTimeout(timer);
      video.removeEventListener("loadeddata", onReady);
      video.removeEventListener("canplay", onReady);
    };

    video.addEventListener("loadeddata", onReady);
    video.addEventListener("canplay", onReady);
  });
}
