# One Gesture

Visual-only One UI style gesture bar overlay (no gesture handling). Draws an
accessibility overlay window that ignores all touches, so the real gesture bar
underneath keeps working.

Build: push to GitHub, the Actions workflow produces `OneGesture-debug`
(app-debug.apk). Or locally: `gradle assembleDebug`.

Setup: install APK, open the app, enable "One Gesture" under Accessibility,
then tune with the sliders (turn on "Alignment guide" to see the overlay area).

## Updating

Every build (local or CI) is signed with `app/debug.keystore`, so updates install in place:

    adb install -r app-debug.apk
