# Askly

A minimal private Android AI chat app built with Kotlin and Jetpack Compose.

## Features

- Single-screen chat UI with Compose
- OpenAI Responses API integration
- ViewModel and StateFlow UI state
- Blue Askly launcher logo
- Local chat history with new, open, and delete chat actions
- Room database for structured local chat storage
- Automatic one-time import from the previous JSON chat storage
- Dark mode and light mode settings
- English and Arabic language support with RTL layout
- Settings saved locally on the device

## Add your key

Open `local.properties` and add:

```properties
OPENAI_API_KEY=your_key_here
```

Then run the app from Android Studio. The key is read at build time and used for direct calls to the OpenAI Responses API.

This project is intended for private testing. Do not distribute the APK with this architecture because a key bundled in a mobile app can be extracted.

## Navigation

- Use the history icon to open saved chats.
- Use the plus icon to create a chat.
- Use the trash icon to delete a chat.
- Open Settings to change theme and language.
- Android system Back returns from Settings or Chat History to the previous screen.

## Local storage

Chat conversations are stored in the local Room database file `askly.db`. Existing chats from older Askly builds are imported automatically on the first launch after the Room migration. Theme and language preferences remain in local app settings.

