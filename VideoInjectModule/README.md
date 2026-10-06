# Video Inject: LSPosed module (test aid for your own app)

Plays a video of your choice in place of the camera **preview** inside one app you scope the module to,
similar to what OBS Virtual Camera does on desktop. Your app does **not** need any change.

## Build with Android Studio (PC)
1. Unzip `VideoInjectModule.zip` and in Android Studio choose File > Open, then pick the `VideoInjectModule` folder.
2. Wait for "Gradle sync" to finish (first time it downloads a lot). If asked to install Android SDK 34, accept.
   If it offers to upgrade the Android Gradle Plugin, choose "Don't remind me again" / skip.
3. Build > Build Bundle(s) / APK(s) > Build APK(s). Click "locate" in the popup.
   The file is `app/build/outputs/apk/debug/app-debug.apk`.
4. Copy `app-debug.apk` to the phone and install it.

Command line alternative (JDK 17 + Android SDK installed): `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
There is also a GitHub workflow in `.github/workflows/build.yml` that builds the same APK online.

## Use (one-time setup)
1. Install the APK. In LSPosed: Modules > Video Inject > enable, and tick **only the app you want to test** in the scope.
2. Open Video Inject (it has its own launcher icon). Card 1: **Choose app** and tap the app in the list.
   Card 2: **Choose video**. Card 4: **Apply to app** and tap **Allow** on the root (Magisk/KernelSU) popup.
3. Swipe the chosen app away from recent apps (first time only), open it once, then open its camera screen.
4. Tap **Check status** in Video Inject. It shows five lines: root, video chosen, video inside the app,
   LSPosed hook loaded, and what the camera preview did last. Green = good, amber = do the hint, red = problem.

## Fit in the circle (new)
- After you choose a video, the module looks for the face automatically (Android's built-in face finder) and turns
  the picture upright if needed. Card 3 **Open simulation** shows the video inside a round preview, exactly the way the
  chosen app draws it (same renderer). Sliders: zoom out/in, left/right, up/down; buttons: rotate 90, mirror,
  frame shape, Auto-fit face, Reset. **Save as final output** stores it; if the video was already applied it is re-applied.
- The face fills about 70 percent of the circle by default ("a little zoomed out").
- If the smart renderer cannot start in the app, the module falls back to plain video playback (Check status says so).

## Later
- Same video plays again every time the app opens the camera.
- New video: Choose video > Apply to app > open the camera screen again.
- Switches (Inject / Loop) re-apply automatically. "Remove video from app" cleans up.
- Closing or force-stopping your app deletes nothing. Never use "Clear data".

## How it works
- **Apply to app** uses root to copy the video and a tiny settings file into the target app's own
  `files/` folder (`vinject.mp4`, `vinject.cfg`). Nothing inside the app's code or manifest is touched.
  The app's folder is taken from Android itself (the app picker), and root is started with `su -M`
  (global mount namespace) because on Android 11+ a root shell started from an app often cannot see
  other apps' data folders otherwise. Several folder paths are tried; if none exists the screen shows the details.
- When LSPosed loads the module into the chosen app, the hook writes `files/vinject.loaded`; **Check status**
  reads it, which proves the LSPosed scope is correct.
- `HookEntry` hooks Camera1 (`setPreviewTexture` / `setPreviewDisplay`) and Camera2/CameraX
  (`createCaptureSession*`, plus `CaptureRequest.Builder.addTarget`) inside the scoped app.
- The real preview surface is drawn by an OpenGL renderer (`GlVideoRenderer`: MediaPlayer into an OES texture, then crop/zoom/rotate/mirror) with plain `MediaPlayer` as fallback; the camera is pointed at a throw-away dummy surface.
- The hook writes a one-line `vinject.status` file in the app folder; **Check setup** reads it back.
- If the video or settings file is missing, the hook does nothing and the real camera is used.

## Limitations
- Needs a rooted phone (Magisk or KernelSU) with LSPosed. First user (profile) only.
- Preview only: photo capture, `ImageReader`/frame analysis and `Camera.PreviewCallback` still get real camera data.
- The first valid surface passed to the capture session is treated as the preview.
- Front-camera mirroring and sensor rotation are not emulated. Audio is muted.
- If the app is uninstalled or its data is cleared, tap Apply again.
- Not compiled or run on a device by the author: if the GitHub build or a feature fails, send the error text.
