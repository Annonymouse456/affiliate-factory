# Codex memory: Affiliate Factory

Date set: 2026-06-30

Rules:

- Claude owns product/page work: `index.html` and `images/promo-<id>.png`.
- Claude deploys page work with `tools/deploy-page-gh.ps1`.
- Codex owns video work only: `videos/clip916-*.mp4`.
- Codex deploys video work with `tools/deploy-video-gh.ps1`.
- Do not full-folder push from an outdated copy.
- Codex should generate/deploy at most 3 product videos per day unless the user explicitly overrides.
- Before any page/product edit, run `tools/refresh-from-live.ps1`.
- Before any video work, run `tools/sweep-missing-videos.ps1` and pick products missing video first.
- Daily reminder automation: `affiliate-sweep-reminder`, 20:00 Asia/Bangkok.

Useful commands:

```powershell
.\tools\refresh-from-live.ps1
.\tools\sweep-missing-videos.ps1
.\tools\today-video-quota.ps1
.\tools\copy-latest-gemini-video.ps1 -ProductId <id>
.\tools\deploy-video-gh.ps1 -ProductId <id>
.\tools\deploy-page-gh.ps1 -ProductId <id>
```
