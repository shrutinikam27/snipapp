# SnipText

A sleek, modern web application that allows users to capture screens, take photos, or upload images, crop them, and instantly extract text using Google's Gemini AI.

## ✨ Features Implemented

*   🖥️ **Native Screen Capture**: Built a custom Capacitor plugin utilizing Android's `MediaProjection` API to securely capture content across the entire OS, optimized for real-time frame synchronization.
*   🫧 **Floating Quick Snip Bubble**: A persistent System Alert Window overlay that hovers across all of your Android apps, allowing you to instantly trigger a screen snip without opening the SnipText app.
*   📐 **Interactive Snipping Overlay**: A native, hardware-accelerated dark overlay that lets you draw a precise "cutout" mask directly over your screen to capture only the exact content you care about.
*   🔄 **Foreground Recovery & Instant Routing**: Engineered highly resilient Android Intent and PendingIntent mechanisms (with Android 14+ background activity overrides) to forcefully snap the SnipText app back to the foreground and instantly route your snippet to the "Extract View" the second you lift your finger.
*   🛠️ **High Compatibility Fixes**: Implemented critical rendering and lifecycle fixes specifically for restrictive OEM environments (like Xiaomi/MIUI), ensuring the snipping overlay remains visible and active.
*   📳 **Haptic Feedback**: Integrated native vibration support to provide tactile confirmation when a screen snip is successfully captured.
*   📸 **In-App Camera & Gallery**: Capture images directly from your device's camera using a custom viewfinder, or select files from your gallery.
*   ⚡ **Image Optimization**: Automatically scales and compresses high-resolution mobile photos to ensure lightning-fast AI extraction without hanging or crashing.
*   🤖 **AI Text Extraction**: Powered by Google's Gemini AI (`gemini-2.0-flash` / `gemini-1.5-flash` models) for high-speed, accurate text extraction from images.
*   📚 **Recent Snips History**: Automatically saves your most recent snippets and their extracted text to local storage so you never lose an important capture.
*   📋 **Copy to Clipboard**: One-click copy of the extracted text for immediate use.
*   🎨 **Modern UI**: Sleek, responsive design built with Tailwind CSS, featuring smooth transitions and animations.

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

### 🏪 Release APK (for Play Store)

To generate a signed release APK for production:

```bash
cd android
.\gradlew.bat assembleRelease
```

> You will need a **keystore** file to sign the release APK. Refer to the [Android signing guide](https://developer.android.com/studio/publish/app-signing) for details.
