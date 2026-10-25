
import fs from "node:fs";
const p = "index.html";
let s = fs.readFileSync(p, "utf8");
if (!s.includes("assets/fonts/fonts.local.css")) {
  s = s.replace("<style>\n", "<style>\n      @import url(\"assets/fonts/fonts.local.css\");\n");
}
if (!s.includes("hf-vignette")) {
  s = s.replace("      .scene {", `      #hf-vignette,
      #hf-grain {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
        pointer-events: none;
      }
      #hf-vignette {
        background: radial-gradient(ellipse at 50% 46%, rgba(0, 0, 0, 0) 52%, rgba(3, 6, 9, 0.34) 100%);
      }
      #hf-grain {
        opacity: 0.05;
        mix-blend-mode: overlay;
        background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.72' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
      }
      .scene {`);
  s = s.replace("      <!-- BGM -->", `      <!-- global grade overlay (grain + vignette) -->
      <div id="hf-vignette" class="clip" data-start="0" data-duration="23" data-track-index="12"></div>
      <div id="hf-grain" class="clip" data-start="0" data-duration="23" data-track-index="13"></div>

      <!-- BGM -->`);
}
fs.writeFileSync(p, s);
console.log("index polished:", s.includes("hf-vignette"), s.includes("fonts.local.css"));
