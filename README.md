# SnipText

A sleek, modern web application that allows users to capture screens, take photos, or upload images, crop them, and instantly extract text using Google's Gemini AI.

## ✨ Features Implemented

*   🖥️ **Native Screen Capture**: Multi-mode capture system supporting both "Within App" and OS-wide "Entire Screen" extraction.
*   🫧 **Branded Floating Bubble**: A persistent overlay with the official SnipText icon that follows you across apps. Featuring micro-animations (scale-up on touch, magnetic snap-to-close).
*   📐 **Interactive Snipping Overlay**: Hardware-accelerated "draw-to-snip" mask for precise text selection directly over any app or website.
*   🔄 **Aggressive Foreground Recovery**: Custom intent-routing to forcefully bring SnipText to the front after a capture, optimized with auto-retry logic for background activity starts.
*   💾 **Public Gallery Integration**: Every capture is automatically timestamped and saved into a dedicated `Pictures/SnipText` folder in your device's memory.
*   🧲 **Magnetic Snap-to-Close**: A user-friendly "X" zone at the bottom of the screen with a magnetic pulling effect to easily dismiss the floating bubble.
*   📸 **Smart OCR Pipeline**: Powered by `gemini-2.0-flash`, processing high-resolution snippets with state-of-the-art text accuracy.
*   📚 **History & Persistence**: Recent captures and extractions are saved locally for offline review.

## 🛠️ Tech Stack

*   **Frontend Framework**: React 18 (via Vite)
*   **Styling**: Tailwind CSS
*   **Animations**: Framer Motion (`motion/react`)
*   **Icons**: Lucide React
*   **Image Cropping**: `react-image-crop`
*   **AI Integration**: Google Gemini API (supporting latest `2.0-flash` models)

## 🚀 How to Use

1.  **Select an Input Method**: Choose between "Capture Screen", "Camera", or "Gallery" on the home screen.
2.  **Capture/Select**: 
    *   If capturing a screen, select the window or tab you want to snip.
    *   If using the camera, take a photo.
    *   If using the gallery, upload an image file.
3.  **Crop**: Drag to highlight the specific area of the image that contains the text you want to extract.
4.  **Extract**: Click the "Snip & Extract" button.
5.  **Copy**: Review the extracted text and click the "Copy" button to save it to your clipboard!

## 🌐 Running Locally (Web)

1. Install dependencies:
   ```bash
   npm install
   ```
2. Start the dev server:
   ```bash
   npm run dev
   ```
3. Open [http://localhost:3000](http://localhost:3000) in your browser.

## 📱 Android APK Build Guide

### Prerequisites

- [Node.js](https://nodejs.org/) (v18+)
- [Android Studio](https://developer.android.com/studio) with Android SDK installed
- Android SDK path: `C:\Users\<YourUsername>\AppData\Local\Android\Sdk`

### Step 1 — Set up the Android SDK path

Create (or update) the file `android/local.properties` with:

```
sdk.dir=C\:/Users/<YourUsername>/AppData/Local/Android/Sdk
```

> ⚠️ Use forward slashes `/` and escape the colon `\:` as shown above.

### Step 2 — Install dependencies

```bash
npm install
```

### Step 3 — Build the web app

```bash
npm run build
```

### Step 4 — Sync web assets to Android

```bash
npx cap sync android
```

### Step 5 — Build the Debug APK

```bash
cd android
.\gradlew.bat assembleDebug
```

> The APK will be generated at:
> ```
> android\app\build\outputs\apk\debug\app-debug.apk
> ```

### Step 6 — Install on a connected device (optional)

Connect your Android phone via USB with **USB Debugging** enabled, then run:

```bash
npx cap run android
```

Or install the APK manually by transferring it to your phone and enabling **Install Unknown Apps** in your phone settings.

---

## 🛠️ Xiaomi/MIUI Troubleshooting

Xiaomi devices (Redmi, Poco, Mi) have extra security layers that might block background app transitions. If the app doesn't snap back to the front after a snip, please enable these permissions:

1. **Overlay Permission**: If the floating bubble doesn't appear, enable "Display over other apps".
2. **Background activity start**: Go to **App Info** -> **Permissions** -> **Other permissions** -> **"Display pop-up windows while running in the background"** and set it to **"Always allow"**.
3. **Restricted Settings**: On Android 13+, if you can't toggle the switch, go to **App Info** -> **3 Dots (top right)** -> **"Allow restricted settings"** first.

---

### 🏪 Release APK (for Play Store)

To generate a signed release APK for production:

```bash
cd android
.\gradlew.bat assembleRelease
```

> You will need a **keystore** file to sign the release APK. Refer to the [Android signing guide](https://developer.android.com/studio/publish/app-signing) for details.
