# Contributing to ShutAppChat

First off, thank you for considering contributing to ShutAppChat! �

## Code of Conduct

By participating in this project, you are expected to uphold a respectful and collaborative environment for all contributors.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check existing issues to avoid duplicates. When creating a bug report, include:

- **Clear title and description**
- **Steps to reproduce** the behavior
- **Expected behavior**
- **Screenshots** if applicable
- **Device information** (Android version, device model)
- **App version**

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating an enhancement suggestion, include:

- **Clear title and description** of the feature
- **Use cases** explaining why this would be useful
- **Possible implementation** if you have ideas

### Pull Requests

1. Fork the repository
2. Create a new branch (\git checkout -b feature/amazing-feature\)
3. Make your changes
4. Commit your changes (\git commit -m 'Add some amazing feature'\)
5. Push to the branch (\git push origin feature/amazing-feature\)
6. Open a Pull Request

## Development Setup

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 34

### Building the Project

1. Clone the repository:
   \\\ash
   git clone https://github.com/yourusername/ShutAppChat-Client.git
   cd ShutAppChat-Client
   \\\

2. Create your \ServerConfig.kt\:
   \\\ash
   cp src/main/java/it/fabiodirauso/shutappchat/config/ServerConfig.example.kt \
      src/main/java/it/fabiodirauso/shutappchat/config/ServerConfig.kt
   \\\

3. Configure your server URLs in \ServerConfig.kt\

4. Build the project:
   \\\ash
   ./gradlew build
   \\\

## Coding Standards

### Kotlin Style Guide

- Follow the official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions small and focused

### Architecture Guidelines

This project follows MVVM architecture:

- **Activities/Fragments**: UI layer only
- **ViewModels**: Business logic and state management
- **Repositories**: Data source abstraction
- **DAOs**: Database operations
- **API Services**: Network calls

### Code Organization

- Place UI components in appropriate packages (\ctivities\, \ragments\, \dapters\)
- Data models in \model\ package
- Database entities in \database\ package
- Utility functions in \utils\ package

### Commit Messages

- Use clear, descriptive commit messages
- Start with a verb in present tense (Add, Fix, Update, Remove)
- Keep the first line under 50 characters
- Add detailed description if needed

Examples:
\\\
Add group avatar upload feature
Fix message deletion crash
Update WebSocket reconnection logic
\\\

## Testing

- Write unit tests for business logic
- Test UI changes on multiple Android versions
- Test on both physical devices and emulators
- Ensure no crashes or ANRs

## Documentation

- Update README.md if you change setup instructions
- Document new APIs or significant changes
- Add KDoc comments for public APIs
- Update CHANGELOG.md for notable changes

## License

By contributing, you agree that your contributions will be licensed under the GPL-3.0 License.

## Questions?

Feel free to open an issue with the \question\ label if you have any questions about contributing!

---

Thank you for contributing to ShutAppChat! 
