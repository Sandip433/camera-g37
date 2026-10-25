# MotoCam G37 (`com.motocam.g37`) — Computational Camera for Motorola Moto G37 5G (`XT2625-5`)

Complete Android 16 (API 35/36) computational photography camera app tailored for the **Motorola Moto G37 5G (`XT2625-5`)** powered by the MediaTek Dimensity 6400 / 6300 SoC, 4 GB LPDDR4X RAM, and 50 MP (`f/1.8`, `0.61µm` Quad-Bayer -> 12.5 MP `1.22µm`) sensor.

## How to Compile into an Installable `.apk` in 3 Minutes on GitHub Actions (Zero Local Setup)

1. Create a new repository on [GitHub](https://github.com/new) named `motocam-g37`.
2. Extract this ZIP archive and push all files to the `main` branch (or drag-and-drop the extracted files into your GitHub repository web uploader).
3. Open your GitHub repository and click the **Actions** tab.
4. The **Build MotoCam G37 APK** workflow (`.github/workflows/android-build.yml`) will automatically start, install JDK 17, Android SDK, NDK r27, and CMake 3.22.1, compile the C++17 native ISP engine (`libmotocam_native.so`) and Kotlin/Jetpack Compose UI, and publish the installable `MotoCam-G37-APK` artifact (`app-debug.apk`).
