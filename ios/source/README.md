# Gintaras — AltStore / SideStore source

Add this URL as a **source** in AltStore or SideStore to install Gintaras and get
updates automatically (like an app store):

```
https://github.com/vytska69/Gintaras/releases/download/apple-latest/apps.json
```

- `apps.json` is generated and published by CI (`.github/workflows/apple-build.yml`)
  on every build, with the version set to the workflow run number and the
  download pointing at the latest `Gintaras-unsigned.ipa` release asset.
- `icon.png` is the source/app icon (referenced from `apps.json`).

## For users

1. Install AltStore or SideStore (one‑time computer setup — see their docs).
2. In the app: **Sources → + → paste the URL above**.
3. Install **Gintaras** from the source. New builds appear as updates
   automatically. Re‑signing happens with each user's own Apple ID; a **paid**
   Apple ID is recommended so the system‑voice extension is kept.
4. Enable the voice: **Settings → Accessibility → Spoken Content → Voices →
   Lithuanian → Gintaras**.
