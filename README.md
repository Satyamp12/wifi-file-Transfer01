# 📱 WiFi File Transfer - Android App

Ek Android app jisse do phone ke beech files transfer kar sakte ho - bina internet, bina router, bina kisi third device ke.

## Kaise Kaam Karta Hai

```
📱 Phone A (App installed)          📲 Phone B (Chrome browser)
  ┌──────────────────┐                ┌──────────────────┐
  │ WiFi Hotspot ON   │  ◄══ WiFi ══► │  Chrome Browser   │
  │ HTTP Server (8080) │              │  http://IP:8080   │
  │ + File Sharing     │              │  Upload/Download  │
  └──────────────────┘                └──────────────────┘
```

- **Phone A** (sender): App chalao, "Start" dabao, files select karo
- **Phone B** (receiver): Phone A ke WiFi hotspot se connect karo, Chrome me URL kholo ya QR scan karo
- Bas! Files dono taraf transfer ho sakti hain

## APK Build Karne Ke Steps (Android Studio)

### Step 1: Android Studio Install Karo
- https://developer.android.com/studio se Android Studio download karo
- Install karo

### Step 2: Project Kholo
- Android Studio open karo
- **File > Open** par click karo
- `android/` folder select karo
- Android Studio Gradle sync karega (internet chahiye first time ke liye)

### Step 3: APK Build Karo
- **Build > Build Bundle(s) / APK(s) > Build APK(s)** par click karo
- APK file ban jayega: `app/build/outputs/apk/debug/app-debug.apk`

### Step 4: Phone Me Install Karo
- APK file phone me transfer karo (USB, Bluetooth, ya email)
- Phone me APK file par tap karo
- "Install from unknown sources" allow karo agar poochha
- App install ho jayega

## Use Kaise Kare

### Sender Phone (jisme app hai):

1. **WiFi File Transfer** app open karo
2. **"Server Start Karo"** button dabao
3. Permissions allow karo (Location - hotspot ke liye, Notifications)
4. App WiFi hotspot on karega aur server start karega
5. Screen pe dikhega:
   - **QR Code** - dusre phone se scan karo
   - **Server URL** - jaise `http://192.168.43.1:8080`
   - **Hotspot name & password** - dusre phone se connect karne ke liye
6. **"Files Share Karo"** button dabao aur files select karo
7. Selected files list me dikhenge - receiver inhe download kar sakta hai

### Receiver Phone (koi app nahi chahiye):

1. Apne phone ka WiFi kholo
2. Sender phone ka WiFi hotspot dhundo (jo naam app pe dikh raha hai)
3. Us hotspot se connect karo (password app pe likha hai)
4. **Chrome browser** kholo
5. Sender app pe jo URL hai waho dalo (jaise `http://192.168.43.1:8080`)
6. Ya sender ke QR code ko kisi QR scanner se scan karo
7. Web page khul jayega:
   - **"Sender ke Files"** - ye download kar sakte ho
   - **Upload area** - apne files drag/drop ya select karke bhej sakte ho
   - **"Tumne Bheje Files"** - ye files sender ke phone pe save ho gayi

## Kya Kya Transfer Kar Sakte Ho

- Photos (JPG, PNG, GIF, WebP)
- Videos (MP4, AVI, MKV, MOV)
- Music (MP3, WAV, OGG)
- Documents (PDF, DOCX, TXT)
- APKs, ZIP files
- Koi bhi file type
- Multiple files ek saath

## Agar Auto-Hotspot Na Chale

Kuch phones pe auto-hotspot kaam nahi karta. Us case me:

1. Phone ke **Settings > Hotspot & Tethering** me jao
2. **WiFi Hotspot** manually ON karo
3. App wapas "Start" dabao
4. App us hotspot ka IP detect kar lega
5. URL aur QR code dikh jayega

## Project Structure

```
android/
├── settings.gradle
├── build.gradle                    # Project-level build
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle                 # App-level build (dependencies)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml      # Permissions + components
        ├── java/com/wifitransfer/
        │   ├── App.kt               # Application class
        │   ├── FileItem.kt           # Data model
        │   ├── SharedFilesManager.kt # File list management
        │   ├── FileServer.kt        # Embedded HTTP server (NanoHTTPD)
        │   ├── FileShareService.kt  # Foreground service + hotspot
        │   └── MainActivity.kt      # Main UI + file picker
        ├── assets/web/
        │   ├── index.html            # Receiver ke browser ki page
        │   ├── css/style.css         # Web UI styling
        │   └── js/app.js            # Upload/download logic
        └── res/
            ├── layout/
            │   ├── activity_main.xml # Main screen layout
            │   └── item_file.xml     # File list item
            ├── values/
            │   ├── strings.xml
            │   ├── colors.xml
            │   └── themes.xml
            ├── drawable/
            │   ├── ic_launcher_background.xml
            │   └── ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/
            │   ├── ic_launcher.xml
            │   └── ic_launcher_round.xml
            └── xml/
                └── file_paths.xml    # FileProvider paths
```

## Tech Stack

- **Kotlin** - App language
- **NanoHTTPD** - Embedded HTTP server (phone ke andar server chalata hai)
- **ZXing** - QR code generation
- **Android LocalOnlyHotspot** - WiFi hotspot without internet
- **Android Storage Access Framework** - File picker
- **Material Components** - UI
- **Vanilla JS/CSS** - Receiver's web page (koi framework, fast loading)

## Requirements

- Android 8.0 (API 26) ya upar
- Phone me WiFi hotspot support (sab modern phones me hai)
- Receiver phone me Chrome browser (ya koi bhi browser)

## Notes

- Files up to ~2GB tak transfer ho sakti hain (phone RAM par depend)
- Received files app ke storage me save hote hain: `Android/data/com.wifitransfer/files/received/`
- Server sirf local network pe accessible hai - bahar se koi connect nahi kar sakta
- App background me bhi chalta rehta hai (foreground service) jab tak "Stop" na dabao
