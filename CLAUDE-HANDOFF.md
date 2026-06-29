# Claude handoff notes

This folder mirrors the live GitHub Pages site:

```text
https://annonymouse456.github.io/affiliate-factory/
```

Repository:

```text
Annonymouse456/affiliate-factory
```

## Before editing

Always start from the latest live repository state. Do not rebuild the site from an old local copy.

Important generated video paths:

```text
videos/clip916-{product-id}.mp4
```

Example:

```text
videos/clip916-pb-anker-nano.mp4
```

## Safe product additions

When adding a product:

1. Add a new product object to the `DATA` array in `index.html`.
2. Add its promo image under `images/`.
3. If no Gemini video exists yet, leave the `clip` field empty or omit it; Codex will generate/deploy videos separately.
4. Add or preserve generated videos under `videos/clip916-{product-id}.mp4`.
5. Keep existing `videos/*.mp4` files unless intentionally replacing a specific product video.

Deploy page/product changes only with:

```powershell
.\tools\deploy-page-gh.ps1 -ProductId <id>
```

Do not use `deploy-page-gh.ps1` for `videos/*`; it will refuse those paths.

## Deploy caution

Deploying a new `index.html` is safe for existing videos as long as the deploy does not delete or overwrite `videos/`.

The risky case is a full-folder deploy from an outdated copy. That can revert newer videos or remove products added by another tool.

Division of work:

| Who | Owns | Deploys | Script |
| --- | --- | --- | --- |
| Claude | Product data in `index.html` and promo images | `index.html` + `images/` | `deploy-page-gh.ps1` |
| Codex | Gemini/Veo videos, max 3 per day | `videos/clip916-*.mp4` | `deploy-video-gh.ps1` |

## Current known deployed Gemini video

```text
videos/clip916-pb-anker-nano.mp4
```

This file was updated from Gemini/Veo and deployed to `main`.
