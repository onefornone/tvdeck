TVDeck 2.2 – build instructions (no coding needed)

1. Open your TVDeck repository on GitHub and delete the old files (or make a new repository).
2. Upload everything inside this folder, including the hidden .github folder and the new "helper" folder.
3. Open the Actions tab. "Build APK" runs automatically (about 8-10 minutes).
   It first builds a tiny helper app for the TV cursor, then bundles it into TVDeck.
4. When it has a green tick, download the "TVDeck-apk" artifact, unzip it and install app-release.apk.

If the build shows a red cross, copy the error lines (they contain "e:") and send them to Claude.
If only the step "Build TV cursor helper" is red, the app still builds, just without the TV cursor.
