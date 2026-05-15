# Auto Klick

Auto Klick is a powerful and flexible auto-clicker application built with Kotlin and Compose Multiplatform. It allows users to automate mouse clicks, key presses, and mouse scrolling with highly customizable settings, including multiple profiles, intelligent mouse locking, and real-time statistics.

## Features

-   **Multi-Profile Support**: Create, save, load, and manage multiple distinct profiles for different automation tasks.
-   **Profile Activation**: Enable or disable individual profiles to control which automations are active.
-   **Configurable Actions**: Add various actions including left/right/middle mouse clicks, scroll up/down, and keyboard key presses.
-   **Action Skipping**: Define how often an action should be skipped (e.g., click every 2nd tick, every 5th tick).
-   **Mouse Location Locking**: Lock the mouse cursor to a specific X/Y coordinate during automation.
-   **KeepControl Mode**: An intelligent mode that pauses automation when you manually move the mouse, resuming when idle. Configurable delay for resuming.
-   **Global Hotkeys**: Assign a global hotkey to each profile to toggle its automation on/off without interacting with the UI.
-   **Real-time Statistics**: Monitor global and per-action Ticks Per Second (TPS) and Actions Per Second (APS), including min, max, avg, and total counts.
-   **Configurable Limits**: Set a maximum TPS limit for automation or disable it for unlimited speed.
-   **Timeout Functionality**: Automatically stop automation after a specified duration.
-   **Tray Icon Support**: Minimize the application to the system tray for discreet operation.
-   **Theme Switching**: Toggle between Light and Dark themes for comfortable viewing.
-   **Intuitive UI**: User-friendly interface with sliders for major fields, clear descriptions, and reset-to-default options.
-   **Platform-Independent Settings**: Profiles are saved in standard OS-specific configuration directories.

## Getting Started

### Prerequisites

-   Java Development Kit (JDK) 17 or higher
-   Gradle (usually bundled with IDEs like IntelliJ IDEA/Android Studio)

### Running from Source

1.  **Clone the repository**:
    ```bash
    git clone https://github.com/your-username/auto-klick.git
    cd auto-klick
    ```
2.  **Open in IntelliJ IDEA**:
    Open the `auto-klick` project in IntelliJ IDEA.
3.  **Run the application**:
    Locate the `main` function in `src/main/kotlin/com/example/autoklick/Main.kt` and click the green 'Run' arrow next to it.

### Building an Executable

You can build a platform-specific executable using Gradle.

1.  **Build for your OS**:
    ```bash
    # For Windows
    ./gradlew packageWindows
    # For macOS
    ./gradlew packageMac
    # For Linux
    ./gradlew packageLinux
    ```
    Executables will be found in the `build/compose/binaries` directory.

## Usage

1.  **Create/Select a Profile**: Use the tabs at the top to switch between profiles or click the `+` button to create a new one.
2.  **Configure Settings**: Adjust the sliders and input fields for TPS limit, polling interval, timeout, mouse lock, and KeepControl mode.
3.  **Add Actions**: Type a key name (e.g., `leftclick`, `space`, `f8`) into the "Quick Add" field and press space to add an action.
4.  **Set Global Hotkey**: Click the "Key: F8" button (or whatever key is displayed) and press your desired global hotkey to assign it.
5.  **Start/Stop**: Click the large "START" or "STOP" button, or use your assigned global hotkey.
6.  **Enable/Disable Profiles**: Use the checkbox on each tab to activate or deactivate a profile. Multiple enabled profiles can run concurrently.

## Contributing

Contributions are welcome! If you have suggestions, bug reports, or want to contribute code, please open an issue or submit a pull request on the GitHub repository.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
