# Affiliate Factory machine workflow

This folder is the local working copy for generating product videos with Gemini and previewing the Affiliate Factory web app.

## Work split

| Who | Owns | Deploys | Script |
| --- | --- | --- | --- |
| Claude | New products, `DATA` in `index.html`, promo images | `index.html` + `images/` only | `tools/deploy-page-gh.ps1` |
| Codex | Gemini/Veo videos, up to 3 clips per day | `videos/clip916-*.mp4` only | `tools/deploy-video-gh.ps1` |

Never do a full-folder push from an outdated copy. That is the main way work gets overwritten.

## Claude product flow

```powershell
.\tools\refresh-from-live.ps1
# edit DATA array in index.html + add images/promo-<id>.png
.\tools\deploy-page-gh.ps1 -ProductId <id>
```

`deploy-page-gh.ps1` refuses `videos/*` paths on purpose.

## Daily flow

1. Open the preview:

```powershell
.\tools\start-preview.ps1
```

The default preview URL is:

```text
http://127.0.0.1:8766/
```

2. In Codex/Chrome, send a product image from `gemini-inputs` to the Gemini Gem.

3. Check which products still need video work:

```powershell
.\tools\sweep-missing-videos.ps1
```

4. After Gemini downloads the finished video, copy the newest MP4 into the correct website path:

```powershell
.\tools\copy-latest-gemini-video.ps1 -ProductId pb-anker-nano
```

The website expects videos at:

```text
videos/clip916-{product-id}.mp4
```

## Prompt choices

Use the Gem instruction that maps:

- `เลือก 1`: cinematic product commercial
- `เลือก 2`: cyberpunk neon
- `เลือก 3`: levitating product with smoke
- `เลือก 4`: water splash commercial

For tech products, start with `เลือก 1` or `เลือก 3`.
For waterproof or fresh/clean products, use `เลือก 4`.

## Important

Before generating, set Gemini's video aspect ratio to portrait or 9:16 in the UI. If the UI remains `Landscape (16:9)`, the output can become 1280x720 even when the prompt says vertical video.

## Publishing

Use GitHub CLI login once:

```powershell
gh auth login --hostname github.com --git-protocol https --web
```

Then deploy a generated video to the live GitHub Pages site:

```powershell
.\tools\deploy-video-gh.ps1 -ProductId pb-anker-nano
```

Codex video deploys are capped at 3 product videos per day by default. Check today's quota:

```powershell
.\tools\today-video-quota.ps1
```

This updates the same path used by the live app:

```text
https://annonymouse456.github.io/affiliate-factory/videos/clip916-pb-anker-nano.mp4
```

If another AI/tool such as Claude edits products, ask it to read `CLAUDE-HANDOFF.md` first and start from the latest GitHub repo state. Deploying `index.html` changes is safe, but a full deploy from an old folder can overwrite newer videos.

## Daily reminder

The Codex automation `affiliate-sweep-reminder` runs daily at 20:00 Asia/Bangkok. It refreshes from live, sweeps for missing videos, checks the remaining 3-video quota, and reports the next safe action. It does not deploy or generate videos by itself.
