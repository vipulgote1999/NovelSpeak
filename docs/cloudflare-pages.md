# Deploying the website

The site is plain HTML, CSS and a 5 KB script in `site/`. There is no build step, no npm dependency
and no framework: the bytes in the repository are the bytes served. That is deliberate — it keeps
the deploy reproducible, the privacy claim true (no third-party origins at runtime) and the page
fast on a phone.

Two hosts publish the same folder:

| Host | URL | What it is for |
|---|---|---|
| Cloudflare Pages | `https://<project>.pages.dev/` | primary site: custom headers, previews per branch, custom domain |
| GitHub Pages | `https://vipulgote1999.github.io/NovelSpeak/` | the existing public privacy policy URL registered in Play Console |

**Constraint that must not be broken:** every internal link and asset path is relative, so the same
files work at `/` on Cloudflare and at `/NovelSpeak/` on GitHub Pages. `node tools/site-check.mjs`
fails on a root-absolute path or a link that leaves the site. Never introduce `<base>` or
`/assets/...`.

## Option A — Cloudflare Pages, Git integration (no secrets, recommended)

1. Cloudflare dashboard → **Workers & Pages → Create → Pages → Connect to Git**.
2. Pick the `NovelSpeak` repository and the `main` branch for production.
3. Build settings:
   - Framework preset: **None**
   - Build command: **leave empty**
   - Build output directory: **`site`**
   - Root directory: **`/`** (repository root)
4. Save and deploy. Cloudflare uploads `site/` as-is and gives you
   `https://novelspeak.pages.dev` (or the name you chose).
5. Add a custom domain under **Custom domains** if you have one; Cloudflare issues the certificate.
6. Leave **Preview deployments** on. Every branch build gets its own URL, which is the fastest way
   to review a change without touching production.

Nothing else is required: `site/_headers` and `site/_redirects` are picked up automatically.

## Option B — GitHub Actions with `wrangler` (token-gated, optional)

`.github/workflows/cloudflare-pages.yml` runs on pushes to `main` and `site/**` and on pull
requests. Its `check` job runs `node tools/site-check.mjs` on every run. Its `deploy` job:

- **no-ops with a notice** when `CLOUDFLARE_API_TOKEN` or `CLOUDFLARE_ACCOUNT_ID` is absent;
- deploys `site/` with `wrangler pages deploy site --project-name novelspeak` when both secrets
  exist.

To enable it:

1. Create a Pages project named `novelspeak` (Option A step 1 is enough; you can also create an
   empty project).
2. Create an API token: **My Profile → API Tokens → Create Token → Custom token**, permission
   **Account → Cloudflare Pages → Edit**, scoped to your account.
3. Copy the **Account ID** from the dashboard (Workers & Pages overview, right-hand column).
4. In the GitHub repository: **Settings → Secrets and variables → Actions → New repository
   secret**. Add `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
5. Push to `main`, or run the workflow manually (**Actions → Website → Run workflow**).

Branch builds deploy as previews named after the branch. Use only one mechanism at a time: if the
Git integration is connected, either remove it or drop the token secrets, so two deploys do not race
for the same project.

## Option C — from this machine, by hand

```bash
npx wrangler@3 pages deploy site --project-name novelspeak --branch=site-redesign
```

`wrangler` opens a browser for login the first time. This is the only command that needs network
access; nothing about the site build does.

## What Cloudflare adds, and GitHub Pages cannot

`site/_headers` sets:

- `Content-Security-Policy` with `default-src 'self'`, no inline script, no external origin;
  `img-src 'self' data:`; `frame-ancestors 'none'`; `upgrade-insecure-requests`.
- `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`,
  `Permissions-Policy` denying geolocation, camera, microphone and payment,
  `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy: same-origin`.
- `Cache-Control: must-revalidate` for HTML; short caches for `assets/`.

`site/_redirects` normalises `/features`, `/voices`, `/changelog`, `/privacy` to their trailing-slash
directories and `/index.html` to `/`.

GitHub Pages ignores both files. The privacy policy therefore still answers at
`https://vipulgote1999.github.io/NovelSpeak/privacy/`, which is the URL registered in Play Console.
Do not remove `site/privacy/index.html`, and do not change its path.

## After the first deploy

```bash
# headers actually served?
curl -sI https://novelspeak.pages.dev/ | grep -i -E "content-security-policy|referrer-policy|x-content-type"
# the Play Console URL still works
curl -sI https://vipulgote1999.github.io/NovelSpeak/privacy/ | head -1
# the contract still holds
node tools/site-check.mjs
```

Then open the preview on a phone and check the five things the static checker cannot:
no horizontal scroll at 320 px, the hero's buttons above the fold at 390 px, the read-along sweep
finishing in under two seconds, 200 % text reflow, and the film playing only after a press.

## Keeping the site current

1. **A new release:** update `SITE.release` in `site/assets/site.js` and the static `href` values on
   the Download buttons (they are the no-JS path), the changelog page, the version in the footer and
   the "latest APK" line. `grep -rn "1\.3\.0" site/` is the whole list.
2. **The Play listing goes live:** set `SITE.playUrl` in `site/assets/site.js`. The status line
   becomes a real link on every page; no markup change is needed.
3. **New screenshots:** drop the source into `docs/`, add a line to the `jobs` table in
   `tools/site-assets.mjs`, run it, then `node tools/site-check.mjs` for the budget.
4. **Before every push:** `node tools/site-check.mjs` (it runs in CI too) and the repo's
   `tools/test-all.sh --quick` for the app side.

## Rollback

- Cloudflare keeps every deployment: **Workers & Pages → project → Deployments → Roll back**.
- GitHub Pages deploys from `main`; revert the commit or re-run the workflow.
- The site lives in its own commits, so `git revert <sha>` on `main` restores the previous site
  without touching the app.

## What was not verified here

No Cloudflare account, token or dashboard access exists on the machine this site was built on, so
the first deploy and the custom domain are the user's step. There is also no Chromium binary, so the
visual checks in the section above are written down as a checklist rather than claimed as done. The
static contract, byte budgets and CSP are verified; the rendering is not.
