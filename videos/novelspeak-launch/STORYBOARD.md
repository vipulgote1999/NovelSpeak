---
format: 1080x1920
duration: 23s
message: "NovelSpeak reads any web novel aloud with offline neural voices — no account, no ads, no meter."
arc: Hook → Product reveal → Real-UI showcase (reading → voices → screen-off) → CTA
audience: web-novel readers on TikTok / Reels / Shorts
mode: autonomous
music: modern upbeat electronic launch score, 120 BPM, warm analog bass, bright plucks, airy pads, tight percussion, rising final hook
---

## Video direction

**Look.** One cohesive "Harbor ink" palette from the app itself: ink ground `#0E1318`, lifted ink
`#161D24`, hairline `#2C3944`, warm white `#F1F6FA`, secondary `#C9D4DE`, muted `#9AA9B8`, mint
signal `#6FE7BE`, amber rule `#F4C66A`. No second palette, no blue/purple "AI" gradients. Ground
carries one very soft radial mint bloom (6–10% opacity) per frame plus a fine grain at 3%; amber is
reserved for the wordmark rule and one accent hairline per frame — never a fill.

**Type.** Playfair Display (serif, 400–600, sentence case, tight −0.01em) for every headline and the
wordmark; Inter (400/500/600) for tracked uppercase labels, captions and the URL. Display sizes
72–140px on 1080 wide; label 22–24px with 0.28em tracking; caption 26–30px. Copy is capped at one
label + one headline + (max) one caption per frame. Nothing sits in the bottom 300px or the right
96px (vertical-video UI safe zone).

**Motion.** Long-tail settles (`power3`), no bounce, no lazy breathing. Masked line reveals rise
from clip; word swaps are hard cuts on the beat; one continuous camera move per frame (slow push or
plate rise), never a move that starts after the copy has resolved. Ground stays still; only content
moves. Holds are deliberate: every frame ends resolved and still for at least its last 0.4s.
Transitions are hard cuts on a sound-design hit; the only element that continues across a boundary is
the F5→F6 screen plate, with a numbered handoff.

**Sound.** Modern upbeat bed under everything, punchy UI-weighted SFX on every cut and reveal:
whoosh on the hook, bass impact on the reveal, click on the reader entry, pop+sparkle on the voice
beat, soft impact+ping on screen-off, cinematic whoosh into the CTA and a chime on the final hit.
No narration, no captions.

**Real-UI law.** Every screen shown is a real device screenshot of the installed app (no rebuilt or
invented UI). Crops remove only the Android status bar and, where present, the test-fixture URL bar;
nothing inside the app is retouched.

## Frame 1 — Hook

- status: animated
- src: compositions/frames/01-hook.html
- duration: 4s
- transition_in: cut
- scene: Ink field; tracked label, then a serif line that swaps "read it" for "Hear it"
- poster: 3.4s
- sfx: whoosh-short
- type: hook
- blueprint: kinetic-type-beats (Adapt)
- focal: type-only
- roles: ground = background (ink + mint bloom), label = supporting, headline = hero

Scene 1 (0.0–1.0s): full-bleed ink ground with a soft mint radial bloom in the upper right; centered
stack, nothing else. A tracked mint label "FOR WEB NOVEL READERS" rises 18px and fades in
(0.15–0.55s), then a 1px amber rule (56px wide) draws from its left edge (0.55–0.9s). Centered
layout, ~55% empty, one depth layer.
Scene 2 (1.0–2.6s): headline enters as two serif lines, per-word staggered masks rising from a clip:
"You don't have to" / "read it." Each word lands on its own beat (0.18s apart), the final period
landing last. Headline is warm white, 118px, centered, ~3.4:1 against the label; the ground holds
completely still.
Scene 3 (2.6–4.0s): hard token swap — "read it." replaces in place with "Hear it." in mint italic
on a single frame cut, same baseline, same size; the amber rule under the label (or a new one under
the headline, 96px) redraws to full width. No camera move; hold to the cut. The stillness sets up the
reveal impact.

## Frame 2 — Product reveal

- status: animated
- src: compositions/frames/02-reveal.html
- duration: 4s
- transition_in: cut
- scene: Real home screen rises under a serif wordmark that assembles letter by letter
- poster: 7.5s
- sfx: impact-bass-1, sparkle
- type: product_intro
- blueprint: logo-assemble-lockup (Adapt)
- focal: assets/stills/home.png
- roles: home = hero (real UI plate) · ground = background · wordmark = hero type · tagline = supporting

Scene 1 (4.0–5.2s): the bass impact lands on the cut. A real home-screen plate (01_home, 1080×2400)
rises from +140px below into the lower 62% of the frame, ~86% width, its top corners just visible
(cropped by the bottom edge), 1px `line` hairline border, radius 24px — zero heavy shadow. Wordmark
letters "N o v e l S p e a k" begin assembling above it (letter-cascade masks, 0.03s stagger).
Asymmetric 45/55: type upper third, plate lower.
Scene 2 (5.2–6.6s): the wordmark completes in Playfair 600, 104px, warm white, tight tracking; a
38px amber rule draws beneath it (0.5s), and the real tagline "A quiet place to read." fades up in
secondary 34px Inter. One soft mint bloom warms the plate's edge.
Scene 3 (6.6–8.0s): the plate pushes very slowly (scale 1.0→1.045, decelerating) while nothing else
enters; the wordmark and rule hold perfectly still. End resolved; still for the last 0.5s so the cut
to the reader reads as a cut into the screen.

## Frame 3 — Reading, followed

- status: animated
- src: compositions/frames/03-reading.html
- duration: 5s
- transition_in: cut
- scene: Real reader full-bleed; the highlighted paragraph is the hero as copy arrives in two beats
- poster: 12.4s
- sfx: click, whoosh-short
- type: feature_showcase
- blueprint: camera-journey (Adapt)
- focal: assets/stills/reader.png
- roles: reader = hero (full-bleed real UI) · type = foreground · scrim = background

Instantiate: keep the camera's cause→effect travel; change the subject from a product panel to the
real reading surface — the "travel" is a slow push from the chapter top onto the amber-highlighted
paragraph and the mint player. No cursor, no rebuilt UI.
Scene 1 (8.0–9.4s): real reader surface full-bleed (status bar + URL bar cropped), entering at scale
1.18 → 1.14 with a 40px upward settle; a top scrim (ink→transparent, 38% height) makes a quiet
headline area. Tracked mint label "ONE TAP · ANY CHAPTER" fades in upper-left at 8.25s. The amber
highlight block and mint player are already visible and stay untouched — real UI.
Scene 2 (9.4–10.8s): serif line "It reads the chapter." rises from a mask at the top (86px), while
the camera continues a decelerating push (1.14 → 1.20) re-centering on the highlighted paragraph.
The player's mint progress line sits in the lower third, never covered by copy.
Scene 3 (10.8–12.2s): in-place token swap — the second line "Follows every word." lands in mint under
the first, and the camera settles completely (no further push) with the highlight centered. A
hairline amber tick connects the label to the paragraph (96px, drawn once).
Scene 4 (12.2–13.0s): full hold. Nothing moves except a barely perceptible grain shimmer; the real
highlight and player carry the proof.

## Frame 4 — Voices, offline

- status: animated
- src: compositions/frames/04-voices.html
- duration: 3s
- transition_in: cut
- scene: Real voice settings plate right; serif "11 voices." left; "On device. Offline."
- poster: 15.4s
- sfx: pop, sparkle
- type: feature_showcase
- blueprint: device-surface-showcase (Adapt)
- focal: assets/stills/voices.png
- roles: voices = hero (real UI plate) · ground = background · headline = hero type

Scene 1 (13.0–13.8s): the voices screen (05_voices) slides in from the right as a tall plate — right
two-thirds, ~72% width, bleeding off the right edge, 1px hairline, radius 24px — while the ink ground
holds and a packaged "pop" lands on the cut. Tracked label "NEURAL VOICES" fades up top-left.
Scene 2 (13.8–14.8s): the hero line "11 voices." assembles word by word on the left in Playfair
(96px), the numeral set larger (1.25×) in mint; "On device. Offline." arrives below in warm white
Inter 30px, two short beats.
Scene 3 (14.8–16.0s): caption "Kokoro · 103 MB · no cloud" types/fades in muted 24px with a 64px
amber tick above it; the plate holds a 1.0→1.02 micro push; type holds still. Resolved, still 0.4s.

## Frame 5 — Screen off, still reading

- status: animated
- src: compositions/frames/05-screenoff.html
- duration: 3s
- transition_in: cut
- scene: Real media-controls card rises on ink; "Screen off." / "Still reading."
- poster: 18.4s
- sfx: impact-bass-2, ping
- type: benefit_highlight
- blueprint: titlecard-reveal (Adapt)
- focal: assets/stills/media-controls.png
- roles: media-controls = hero (real UI card, monochrome brand treatment) · ground = dim background
- handoff_out: media-controls card at left=65px top=819px width=950px scale=1.0 opacity=1 brightness=1, static at the cut

Scene 1 (16.0–16.8s): a real media-controls card (cropped from the device's actual lock-screen media
player and color-treated to the ink system — the card, title, "Playing · 6/27" and transport are real
UI) rises centered to ~88% frame width against the ink ground, dimmed to 84% brightness; "Screen off."
in serif 92px settles above it at 16.2s on a soft bass impact. Centered, one hero, generous negative
space.
Scene 2 (16.8–17.8s): the card brightens to 100% and catches one mint glint pass (CSS sheen, not
UI-drawn); "Still reading." lands in mint 92px beneath the headline as an in-place second line, timed
to the ping.
Scene 3 (17.8–19.0s): caption "Media controls, even locked." fades in muted 24px tracked; everything
holds completely still to the cut. No push, no breathing.

## Frame 6 — CTA

- status: animated
- src: compositions/frames/06-cta.html
- duration: 4s
- transition_in: cut
- scene: Plate clears; wordmark assembles; tagline and repo URL hold on the final hit
- poster: 22.6s
- sfx: whoosh-cinematic, chime
- type: cta
- blueprint: logo-assemble-lockup (Reproduce)
- focal: wordmark
- roles: ground = background · wordmark = hero · tagline/url = supporting
- handoff_in: media-controls card continues from F5 at left=65px top=819px width=950px scale=1.0 opacity=1 on frame entry; then scale→0.78, y→-30px, opacity→0 by +0.6s

Scene 1 (19.0–20.0s): the F5 screen plate continues exactly as handed off, then scales down and
fades out over the first 0.6s while a cinematic whoosh sweeps; the ink ground is left alone. The
tracked label "FREE · OFFLINE · NO ACCOUNT" fades up centered at 19.7s in mint 22px.
Scene 2 (20.0–22.0s): "NovelSpeak" assembles centered-upper in Playfair 600 at 122px — letters mask
up on a 0.045s stagger from the wordmark's center outward; a 96px amber rule draws beneath it at
21.7s on the rise of the final hook.
Scene 3 (22.0–23.0s): the final musical hit lands: tagline "A quiet place to read." fades up in
secondary 34px, and "github.com/vipulgote1999/NovelSpeak" arrives beneath in mint Inter 26px with
0.06em tracking. Everything holds perfectly still for the last 0.8s — no exit animation, the film
just ends on the lockup.