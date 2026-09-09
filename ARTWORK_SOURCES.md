# USportz artwork sources

USportz uses a layered artwork strategy:

1. **Live feed artwork first** — ESPN schedule data supplies league and competitor logo URLs when available.
2. **Curated brand fallback** — `BrandAssets.kt` supplies real league/promotion logos when a schedule feed has no artwork.
3. **Generated gradient fallback** — the existing brand palette remains only as a visual fallback if an image cannot be loaded.

Curated Wikimedia Commons assets currently used include:

- WWE official logo: `WWElogo2014.png`
- WWE Raw: `RAW.png`
- WWE SmackDown: `SmackDown_2019.png`
- WWE NXT: `NXT_LOGO.png`
- AEW primary logo: `All Elite Wrestling logo 2023.png`
- AEW Dynamite: `AEW Dynamite logo (simplified).jpg`
- AEW Double or Nothing: `AEW Double or Nothing logo.png`
- AEW Battle of the Belts: `AEW Battle of the Belts logo.png`
- TNA: `TNA-logo-June-2024-v2.png`
- ROH: `Ring of Honor Logo Final(1).png`

ESPN league logo URLs are used for major leagues where available.

Asset URLs are intentionally resolved at runtime rather than storing third-party binaries in the repository. Coil caches loaded artwork on-device, keeping the APK smaller while allowing artwork to be updated independently of the app release.
