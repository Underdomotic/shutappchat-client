# ShutAppChat - Android Client

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="ShutAppChat Logo" width="120"/>
</p>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)

A modern, secure, and feature-rich Android messaging application built with Kotlin and Material Design 3.

##  Features

-  **End-to-end encryption** - AES-CBC encryption for media files
-  **Real-time messaging** - WebSocket-based instant communication
-  **Group chats** - Create and manage group conversations with profile pictures
-  **Media sharing** - Send images, videos, and files with built-in editor
-  **User profiles** - Customizable avatars and status
-  **Smart notifications** - Configurable notification system
-  **Dark mode** - Full Material Design 3 theming support
-  **Modern UI** - Beautiful and intuitive user interface
-  **Privacy focused** - Message deletion and privacy controls

##  Architecture

This app follows modern Android development best practices:

- **MVVM Architecture** - Clean separation of concerns
- **Kotlin Coroutines & Flow** - Reactive programming and async operations
- **Room Database** - Local data persistence
- **Retrofit** - RESTful API communication
- **WebSocket** - Real-time bidirectional communication
- **Material Design 3** - Modern UI components

##  Tech Stack

- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture Components**: ViewModel, LiveData, Room
- **Networking**: Retrofit, OkHttp, WebSocket
- **Image Loading**: Glide
- **Dependency Injection**: Manual DI with Repository pattern
- **UI**: Material Design 3, ViewBinding

##  Prerequisites

- Android Studio Hedgehog or later
- JDK 17 or later
- Android SDK 34
- Gradle 8.2+

##  Getting Started

### 1. Clone the repository

\\\ash
git clone https://github.com/yourusername/ShutAppChat-Client.git
cd ShutAppChat-Client
\\\

### 2. Configure your server

Create \ServerConfig.kt\ from the template:

\\\ash
cp src/main/java/it/fabiodirauso/shutappchat/config/ServerConfig.example.kt \
   src/main/java/it/fabiodirauso/shutappchat/config/ServerConfig.kt
\\\

Edit \ServerConfig.kt\ and replace the placeholder URLs with your actual server endpoints:

\\\kotlin
object ServerConfig {
    const val WS_URL = "wss://your-server.example.com/ws"
    const val API_BASE_URL = "https://your-server.example.com/api/v2/"
    const val APP_LINKS_URL = "https://your-server.example.com/api/v2/"
}
\\\

> **Important**: Never commit \ServerConfig.kt\ to version control. It's already in \.gitignore\.

### 3. Build and run

Open the project in Android Studio and:

1. Sync Gradle files
2. Build the project
3. Run on an emulator or physical device

Or use command line:

\\\ash
./gradlew assembleDebug
./gradlew installDebug
\\\

##  Project Structure

\\\
app/
 src/main/
    java/it/fabiodirauso/shutappchat/
       activities/        # UI Activities
       adapters/          # RecyclerView Adapters
       api/               # API interfaces and DTOs
       config/            # App configuration
       database/          # Room database entities and DAOs
       fragments/         # UI Fragments
       models/            # Data models
       network/           # Networking layer
       repositories/      # Data repositories
       utils/             # Utility classes
       viewmodels/        # ViewModels
       websocket/         # WebSocket implementation
    res/                   # Resources (layouts, drawables, etc.)
 build.gradle.kts           # App-level build configuration
\\\

##  Configuration

### Server Requirements

This client requires a compatible server backend that provides:

- RESTful API for user management, messaging, and media upload
- WebSocket server for real-time messaging
- AES-CBC encrypted media storage

> **Note**: The server implementation is not included in this open-source release.

### API Endpoints

The app expects the following API structure:

- \POST /api/v2/auth/login\ - User authentication
- \POST /api/v2/auth/register\ - User registration
- \GET /api/v2/messages\ - Fetch messages
- \POST /api/v2/upload\ - Upload media files
- \GET /api/v2/groups\ - Fetch groups
- And more...

##  Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes:

1. Fork the repository
2. Create your feature branch (\git checkout -b feature/AmazingFeature\)
3. Commit your changes (\git commit -m 'Add some AmazingFeature'\)
4. Push to the branch (\git push origin feature/AmazingFeature\)
5. Open a Pull Request

##  License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

##  Author

**Fabio Di Rauso**

- GitHub: [@fabiodirauso](https://github.com/fabiodirauso)
- Website: [fabiodirauso.it](https://fabiodirauso.it)

##  Acknowledgments

- Material Design 3 guidelines
- Android Jetpack libraries
- The Kotlin community

##  Support

If you have any questions or issues, please open an issue in the GitHub repository.

---

**Note**: This is the client-side implementation only. A compatible server is required for full functionality.
