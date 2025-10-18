#  Guida alla Contribuzione

Grazie per l'interesse nel contribuire a **ShutAppChat**! 

Questa guida ti aiuterà a iniziare con il processo di contribuzione.

##  Indice

- [Codice di Condotta](#codice-di-condotta)
- [Come Posso Contribuire?](#come-posso-contribuire)
- [Processo di Sviluppo](#processo-di-sviluppo)
- [Linee Guida per il Codice](#linee-guida-per-il-codice)
- [Commit Messages](#commit-messages)
- [Pull Request](#pull-request)

##  Codice di Condotta

Partecipando a questo progetto, ti impegni a rispettare il nostro [Codice di Condotta](CODE_OF_CONDUCT.md).

### Principi Base
-  Sii rispettoso e inclusivo
-  Accetta critiche costruttive
-  Concentrati su ciò che è meglio per la community
-  Non usare linguaggio offensivo o immagini inappropriate
-  Non molestare altri contributori

##  Come Posso Contribuire?

### Segnalare Bug 

Prima di creare una issue per un bug:
1. **Controlla** se esiste già una issue simile
2. **Usa** il template per i bug report
3. **Includi**:
   - Versione app (es. v1.2.9)
   - Versione Android (es. Android 13)
   - Dispositivo (es. Samsung Galaxy S23)
   - Steps per riprodurre il bug
   - Screenshot/video se possibile
   - Log rilevanti

### Suggerire Nuove Funzionalità 

Per proporre una nuova feature:
1. **Verifica** che non sia già stata proposta
2. **Spiega** il problema che risolve
3. **Descrivi** la soluzione proposta
4. **Considera** alternative già valutate

### Migliorare la Documentazione 

Contributi alla documentazione sono sempre benvenuti:
- Correggere typo
- Migliorare chiarezza spiegazioni
- Aggiungere esempi
- Tradurre in altre lingue

### Scrivere Codice 

#### Aree di Contribuzione

**Frontend (Android Client)**
- UI/UX improvements
- Nuove features
- Bug fixes
- Performance optimizations
- Accessibility improvements

**Documentazione**
- README miglioramenti
- Code comments
- Tutorial
- API documentation

##  Processo di Sviluppo

### 1 Fork & Clone

\\\ash
# Fork del repository su GitHub
# Poi clona il tuo fork
git clone https://github.com/TUO_USERNAME/shutappchat-client.git
cd shutappchat-client

# Aggiungi upstream remote
git remote add upstream https://github.com/Underdomotic/shutappchat-client.git
\\\

### 2 Crea un Branch

\\\ash
# Aggiorna il tuo main
git checkout main
git pull upstream main

# Crea un branch per la tua feature
git checkout -b feature/nome-feature
# Oppure per un bugfix
git checkout -b fix/nome-bug
\\\

**Convenzioni Nomi Branch:**
- \eature/\ - Nuove funzionalità
- \ix/\ - Bug fixes
- \docs/\ - Documentazione
- \
efactor/\ - Refactoring codice
- \	est/\ - Aggiungere/migliorare test

### 3 Sviluppa

- Scrivi codice pulito e leggibile
- Segui le [Linee Guida Kotlin](https://kotlinlang.org/docs/coding-conventions.html)
- Aggiungi test se appropriato
- Aggiorna la documentazione

### 4 Test

\\\ash
# Run unit tests
./gradlew test

# Run lint
./gradlew lint

# Run instrumented tests
./gradlew connectedAndroidTest
\\\

### 5 Commit

Vedi [Commit Messages](#commit-messages) per le convenzioni.

\\\ash
git add .
git commit -m "feat: aggiungi supporto per voice messages"
\\\

### 6 Push & Pull Request

\\\ash
git push origin feature/nome-feature
\\\

Poi apri una Pull Request su GitHub.

##  Linee Guida per il Codice

### Kotlin Style Guide

Segui le [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).

**Esempi:**

 **DO:**
\\\kotlin
// Nomi descrittivi
class MessageRepository(private val messageDao: MessageDao) {
    suspend fun sendMessage(message: Message): Result<Unit> {
        return try {
            messageDao.insert(message)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
\\\

 **DON'T:**
\\\kotlin
// Nomi poco chiari, logica complessa
class MsgRepo(val d: MessageDao) {
    fun send(m: Message) {
        d.insert(m)
    }
}
\\\

### Architettura MVVM

Segui il pattern MVVM esistente:

\\\
View (Activity/Fragment)
  
ViewModel (business logic)
  
Repository (data layer)
  
Data Sources (Room DB + Network)
\\\

### Naming Conventions

| Tipo | Convenzione | Esempio |
|------|-------------|---------|
| Classes | PascalCase | \MessageViewModel\ |
| Functions | camelCase | \sendMessage()\ |
| Variables | camelCase | \userName\ |
| Constants | UPPER_SNAKE_CASE | \MAX_RETRY_COUNT\ |
| XML IDs | snake_case | \tn_send_message\ |
| Layouts | activity/fragment_name | \ctivity_chat.xml\ |

### Code Quality

- **No Magic Numbers**: Usa costanti
  \\\kotlin
  //  Bad
  if (messages.size > 50) { ... }
  
  //  Good
  private const val MAX_MESSAGES_CACHE = 50
  if (messages.size > MAX_MESSAGES_CACHE) { ... }
  \\\

- **Null Safety**: Sfrutta le feature Kotlin
  \\\kotlin
  //  Good
  val username = user?.username ?: "Anonymous"
  \\\

- **Coroutines**: Usa per operazioni async
  \\\kotlin
  viewModelScope.launch {
      val result = repository.fetchMessages()
      // Handle result
  }
  \\\

##  Commit Messages

Usa il formato [Conventional Commits](https://www.conventionalcommits.org/).

### Format

\\\
<type>(<scope>): <subject>

<body>

<footer>
\\\

### Types

- \eat\: Nuova feature
- \ix\: Bug fix
- \docs\: Documentazione
- \style\: Formattazione (no logic change)
- \
efactor\: Refactoring codice
- \perf\: Performance improvement
- \	est\: Aggiungere/modificare test
- \chore\: Build, dependencies, tools

### Esempi

\\\ash
feat(chat): add voice message recording

Implement audio recording using MediaRecorder API.
Add UI button for voice messages in chat screen.

Closes #123
\\\

\\\ash
fix(auth): prevent crash on invalid JWT token

Handle expired tokens gracefully by redirecting to login.

Fixes #456
\\\

\\\ash
docs(readme): update installation instructions
\\\

##  Pull Request

### Checklist

Prima di aprire una PR, verifica:

- [ ] Il codice compila senza errori
- [ ] Tutti i test passano
- [ ] Lint non ha warning
- [ ] Documentazione aggiornata
- [ ] Commit messages seguono convenzioni
- [ ] Branch è aggiornato con \main\
- [ ] Screenshots/video se cambio UI

### Template PR

\\\markdown
## Descrizione
Breve descrizione delle modifiche.

## Tipo di Cambiamento
- [ ] Bug fix (non-breaking change)
- [ ] Nuova feature (non-breaking change)
- [ ] Breaking change
- [ ] Documentazione

## Come è stato testato?
Descrivi i test effettuati.

## Screenshots
(Se applicabile)

## Issue Collegate
Closes #123
\\\

### Review Process

1. **Automated Checks**: CI/CD esegue test e lint
2. **Code Review**: Almeno 1 approvazione richiesta
3. **Testing**: Verifica su dispositivo reale
4. **Merge**: Squash and merge in \main\

##  Domande?

Se hai domande:
-  Email: info@fabiodirauso.it
-  Apri una [Discussion](https://github.com/Underdomotic/shutappchat-client/discussions)
-  Leggi la [Documentazione](https://shutappchat.fabiodirauso.it/docs.html)

##  Riconoscimenti

Tutti i contributori saranno aggiunti alla [Contributors List](CONTRIBUTORS.md).

---

Grazie per contribuire a ShutAppChat! 
