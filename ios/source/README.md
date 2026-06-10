# Gintaras — AltStore PAL distribution (EU, no computer for users)

Distributing Gintaras through **AltStore PAL** so EU users install it with **no
computer** and get automatic updates. This needs a paid Apple Developer account
and Apple **notarization for alternative distribution**.

The flow has manual (App Store Connect, browser) steps and automated (CI) steps.

## CI does (automated)

`.github/workflows/appstore-upload.yml` — signs the app + GintarasVoice extension
(App Store Connect API key, automatic cloud signing) and **uploads the build to
App Store Connect**. That build is what you then submit for Notarization.

Add 4 repository secrets (Settings → Secrets and variables → Actions):

| Secret | What |
|---|---|
| `APPLE_TEAM_ID` | your 10‑char Team ID |
| `APP_STORE_CONNECT_KEY_ID` | App Store Connect API key id |
| `APP_STORE_CONNECT_ISSUER_ID` | App Store Connect API issuer id |
| `APP_STORE_CONNECT_PRIVATE_KEY` | base64 of the API key `.p8` |

## You do (one‑time, in the browser)

1. **App record** — App Store Connect → Apps → **+** → bundle id
   `com.rosasoft.wintalker`. (Run the workflow once first so the bundle id exists.)
2. **EU Alternative Terms** — agree to the *Alternative Terms Addendum for Apps in
   the EU* in App Store Connect (Business → Agreements).
3. **Register with AltStore PAL** — register your Developer ID via AltStore's REST
   API, get a token, then App Store Connect → Users and Access → **Integrations →
   Marketplace**, paste the token, select Gintaras.
4. **Submit for Notarization** — App Store Connect → Gintaras → App Review → change
   type to **Notarization** → Submit. Apple reviews it (fewer guidelines than the
   App Store).

## Step 2 (CI, after notarization is approved)

Once notarized, the build's **Alternative Distribution Package (ADP)** is
downloaded via AltStore's REST API and hosted, and an **`apps-pal.json`** source
is generated (with the `marketplaceID`). Users add that one source URL in
AltStore PAL → install Gintaras → auto‑updates, no computer.

> This second step is wired up after you've completed notarization (it needs your
> AltStore PAL token + the notarized build). `icon.png` here is the app/source icon.

Refs: <https://faq.altstore.io/developers/distribute-with-altstore-pal>,
<https://developer.apple.com/help/app-store-connect/managing-alternative-distribution/submit-for-notarization/>
