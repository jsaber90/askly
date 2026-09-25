# Askly

A minimal private Android AI chat app built with Kotlin and Jetpack Compose.

## Features

- Single-screen chat UI with Compose
- OpenAI Responses API integration
- ViewModel and StateFlow UI state
- Blue Askly launcher logo

## Add your key

Open `local.properties` and add:

```properties
OPENAI_API_KEY=your_key_here
```

Then run the app from Android Studio. The key is read at build time and used for direct calls to the OpenAI Responses API.

This project is intended for private testing. Do not distribute the APK with this architecture because a key bundled in a mobile app can be extracted.
