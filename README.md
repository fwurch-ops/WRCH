# WRCH Android v0.3

Native Android bridge for WRCH + Health Connect.

v0.3:
- Uses the existing WRCH account.
- Reads Health Connect steps, weight, exercise sessions and distance.
- Maps walking, running, cycling and swimming into Move.
- Maps strength/weights/classes into Train.
- Imports every Health Connect exercise into WRCH's canonical `user_activities`.
- Uses the Health Connect record ID for duplicate-safe repeated syncs.
- Preserves the source label, e.g. Samsung Health.
- Imported activities can feed Move, Train, compatible Challenges and the Friends feed.

Cloud build:
- `.github/workflows/build-apk.yml` builds `app-debug.apk` with GitHub Actions.
- No local PC is required once the project is uploaded to GitHub.

This repository currently contains the native Android source for testing WRCH with Health Connect.
