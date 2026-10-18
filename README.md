# Indra: Android Network Penetration Testing Suite

Indra is a modern Android network analysis and penetration testing suite built with Jetpack Compose, Kotlin, and Material 3.

## Features

- Map your local network
- Fingerprint hosts' operating systems and open ports
- Integrated traceroute
- Man-in-the-middle (MITM) attacks
- Password sniffing
- DNS spoofing
- Session hijacking
- Packet forging
- Login cracking
- Exploit finder (Metasploit integration)

## Requirements

- **Rooted** Android 8.0 (API 26) or newer
- Root manager: **Magisk** (recommended), KernelSU, or SuperSU
- Android SDK 34, JDK 17

## Building

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
export KEYSTORE_FILE=/path/to/keystore
export KEYSTORE_PWD=password
export KEYSTORE_ALIAS=alias
export KEYSTORE_ALIAS_PWD=password
./gradlew assembleRelease

# Run tests
./gradlew test
```

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM, Hilt DI |
| Navigation | Navigation Compose |
| Async | Kotlin Coroutines, Flow |
| Native | JNI (IndraClient) |

## Project Structure

```
indra/
├── src/main/java/org/indra/android/
│   ├── IndraApplication.kt    # Hilt Application
│   ├── MainActivity.kt        # Single Activity + Compose
│   ├── core/
│   │   ├── system/            # IndraSystem, ToolBox, PluginManager
│   │   ├── client/            # JNI bridge (IndraClient)
│   │   └── child/             # Child process management
│   ├── data/
│   │   ├── model/             # Data classes
│   │   └── repository/        # Network & plugin repositories
│   ├── di/                    # Hilt modules
│   └── ui/
│       ├── theme/             # Material 3 theme
│       ├── navigation/        # NavHost
│       ├── screens/           # Home, Plugin, Settings
│       └── components/        # Reusable components
```

## License

GPL v3 or later. See [LICENSE](LICENSE).
