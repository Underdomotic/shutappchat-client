#  ShutAppChat - Secure Messaging App

[![GPLv3 License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24-orange.svg)](https://developer.android.com/about/versions/nougat)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34-orange.svg)](https://developer.android.com/about/versions/14)

**ShutAppChat** è un'applicazione di messaggistica Android open-source con focus sulla **privacy** e **sicurezza**. Costruita con Kotlin e architettura MVVM moderna.

![ShutAppChat Banner](docs/banner.png)

##  Caratteristiche Principali

-  **Messaggistica Real-Time** via WebSocket
-  **Privacy-First**: Zero persistenza messaggi sul server
-  **Gruppi** con modalità OPEN/RESTRICTED
-  **Media Sharing** con crittografia e obfuscazione file
-  **Screenshot Blocking** (FLAG_SECURE Android)
-  **Material Design 3** UI moderna e fluida
-  **Offline-First** con Room Database locale
-  **Autenticazione JWT** sicura

##  Architettura

```

  ShutAppChat Android Client (MVVM)      
   Material Design 3 UI                 
   Room Database (SQLite)               
   Retrofit 2 + OkHttp 4                
   Kotlin Coroutines + Flow             

          
           HTTPS/WSS (TLS 1.2+)
          

  Backend Server                          
   REST API (PHP 8+)                     
   WebSocket Server (Go 1.21+)          
   MariaDB 10.5+ Database                

```

###  Principio Cardine: Zero Persistenza

I messaggi **NON vengono salvati sul server**. Vanno direttamente da client a client via WebSocket in tempo reale. Solo i messaggi offline (destinatario non connesso) vengono temporaneamente salvati e **auto-eliminati immediatamente** dopo la consegna.

##  Tech Stack

### Client Android (Kotlin)
- **Versione:** 1.2.9 (versionCode: 10209)
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Language:** Kotlin 2.0+

### Librerie Principali
| Libreria | Versione | Uso |
|----------|----------|-----|
| Room Database | 2.6+ | Local storage (SQLite) |
| Retrofit 2 | 2.9+ | HTTP client API |
| OkHttp 4 | 4.12+ | WebSocket + networking |
| Kotlin Coroutines | 1.7+ | Async operations |
| Material Design 3 | 1.11+ | UI components |
| Glide | 4.16+ | Image loading |
| ExoPlayer | 2.19+ | Video/audio playback |

##  Getting Started

### Prerequisiti
- Android Studio Hedgehog (2023.1.1) o superiore
- JDK 17+
- Android SDK con Build Tools 34.0.0
- Gradle 8.2+

### Installazione

1. **Clone il repository**
```bash
git clone https://github.com/Underdomotic/shutappchat-client.git
cd shutappchat-client
```

2. **Apri con Android Studio**
```bash
# Apri il progetto in Android Studio
# File > Open > seleziona la cartella del progetto
```

3. **Configura l'endpoint API**

Modifica il file \app/src/main/java/com/shutapp/chat/config/ApiConfig.kt\:
```kotlin
object ApiConfig {
    const val BASE_URL = "https://shutappchat.fabiodirauso.it/api/v2/"
    const val WS_URL = "wss://shutappchat.fabiodirauso.it/ws"
}


4. **Build & Run**
```
./gradlew assembleDebug
```
Oppure usa Android Studio: Run > Run 'app'

##  Struttura Progetto

```
shutappchat-client/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/shutapp/chat/
│   │   │   │   ├── data/           # Repository, DAO, Entities
│   │   │   │   ├── ui/             # Activities, Fragments, ViewModels
│   │   │   │   ├── network/        # API Service, WebSocket
│   │   │   │   ├── util/           # Utility classes
│   │   │   │   └── ShutAppChatApp.kt
│   │   │   ├── res/                # Resources (layouts, drawables)
│   │   │   └── AndroidManifest.xml
│   │   └── test/                   # Unit tests
│   └── build.gradle.kts
├── docs/                            # Documentazione extra
├── .github/                         # GitHub Actions CI/CD
├── LICENSE                          # GNU GPL v3
└── README.md                        # Questo file
```


##  Sicurezza & Privacy

### Implementazioni Privacy
-  **Zero Server Persistence**: Messaggi real-time non toccano il database
-  **TLS 1.2+ Encryption**: Tutte le comunicazioni sono crittografate in transito
-  **JWT Authentication**: Token sicuri con scadenza 7 giorni
-  **FLAG_SECURE**: Blocco screenshot/screen recording su Android
-  **Media Obfuscation**: File salvati con UUID random, no EXIF metadata
-  **Auto-Delete**: Eliminazione automatica messaggi pending post-consegna

### Conformità
-  **GDPR Compliant**: Data minimization, no tracking
-  **Local-First**: Dati sensibili solo su device utente
-  **No Analytics**: Zero tracciamento utente

##  Documentazione

- **[Guida Utente](https://shutappchat.fabiodirauso.it/guide.html)** - Come usare l'app
- **[Documentazione Tecnica](https://shutappchat.fabiodirauso.it/docs.html)** - Architettura dettagliata
- **[API Reference](https://shutappchat.fabiodirauso.it/api-docs.html)** - Endpoint REST e WebSocket
- **[Whitepaper](docs/WHITEPAPER.md)** - Protocollo di sicurezza completo

##  Contribuire

Contributi sono benvenuti! Per favore leggi la [Guida Contribuzione](CONTRIBUTING.md) prima di inviare pull request.

### Come Contribuire
1. Fai un fork del progetto
2. Crea un branch per la tua feature (\git checkout -b feature/AmazingFeature\)
3. Commit le modifiche (\git commit -m 'Add some AmazingFeature'\)
4. Push al branch (\git push origin feature/AmazingFeature\)
5. Apri una Pull Request

### Aree di Contribuzione
-  Bug fixes
-  Nuove features
-  Miglioramenti documentazione
-  Traduzioni (i18n)
-  UI/UX improvements
-  Ottimizzazioni performance

##  Testing

```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Run lint checks
./gradlew lint
```

##  Build Release

```bash
# Build release APK
./gradlew assembleRelease

# Build release Bundle (AAB)
./gradlew bundleRelease
```

##  Roadmap

- [ ] **v1.3.0**
  - [ ] End-to-End Encryption (E2EE) con Signal Protocol
  - [ ] Voice Messages
  - [ ] Group Video Calls
  - [ ] Desktop Client (Electron)

- [ ] **v1.4.0**
  - [ ] Self-Destruct Messages con timer
  - [ ] File sharing fino a 100MB
  - [ ] Tema personalizzabile

- [ ] **v2.0.0**
  - [ ] Multi-device sync
  - [ ] Backup crittografato cloud
  - [ ] iOS Client

##  Licenza


Questo progetto è rilasciato sotto **GNU GPL v3** - vedi il file [LICENSE](LICENSE) per dettagli.

### Componenti Progetto
-  **Client Android**: GNU GPL v3 (Open Source)
-  **Backend Server**: Proprietario (API pubblica disponibile)

##  Autore

**Fabio Di Rauso**
- Website: [fabiodirauso.it](https://fabiodirauso.it)
- Email: info@fabiodirauso.it
- Project: [shutappchat.fabiodirauso.it](https://shutappchat.fabiodirauso.it)

##  Ringraziamenti

Un ringraziamento speciale a:
- [Room Database](https://developer.android.com/training/data-storage/room) - Local persistence
- [Retrofit](https://square.github.io/retrofit/) - HTTP client
- [OkHttp](https://square.github.io/okhttp/) - Network library
- [Material Design](https://m3.material.io/) - UI framework
- [Glide](https://github.com/bumptech/glide) - Image loading
- [ExoPlayer](https://github.com/google/ExoPlayer) - Media playback

##  Status

![GitHub stars](https://img.shields.io/github/stars/Underdomotic/shutappchat-client?style=social)
![GitHub forks](https://img.shields.io/github/forks/Underdomotic/shutappchat-client?style=social)
![GitHub issues](https://img.shields.io/github/issues/Underdomotic/shutappchat-client)
![GitHub pull requests](https://img.shields.io/github/issues-pr/Underdomotic/shutappchat-client)

---

<p align="center">
  Fatto con  per la privacy
</p>
