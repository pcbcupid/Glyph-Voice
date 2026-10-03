# Local development setup

**Workshop/web users:** use [installation/README.md](../installation/README.md).
The one-command installers provide private Python, Node, dependencies and the
model. They do not install Arduino/Android tools. Android users installing an APK
need neither these development tools nor the web installer. The rest of this page
is for building Android/firmware from source.

Install JDK 17, Android SDK Platform 36 / Build Tools 35.0.0, Python 3.11+, Git,
and the pinned Arduino dependencies in [firmware/README.md](../firmware/README.md).
The Gradle 8.13 wrapper and its distribution checksum are included.

```sh
git clone git@github.com:pcbcupid/Glyph-Voice.git
cd Glyph-Voice
python3 tools/fetch_assets.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Set `JAVA_HOME` and `ANDROID_HOME` to your own installations, or open the project
in Android Studio and select JDK 17 for Gradle. Keep `local.properties`, private
credentials and signing keys outside Git. On the prepared maintainer workstation,
`source tools/android-env.sh` selects the installed JDK/SDK; its `GLYPH_JDK_DIR`
and `GLYPH_ANDROID_SDK` overrides allow different paths.

## Development simulator

Use a Python virtual environment and `pip install -r tools/requirements.txt`.
The simulator takes a mono PCM16 WAV and can send valid/malformed/interrupted
recordings for transport checks. Connect the development computer to the phone's
hotspot or the same trusted router, then start the simulator:

```sh
python tools/glyph_simulator.py sample.wav
```

In **Connect Glyph**, enter the development computer's private LAN IPv4 address
and simulator port (default 8080), not `0.0.0.0`. No discovery packets are sent.
The simulator serves WebSocket `/audio`. It does not emulate
physical I2S capture or the board's BOOT button. Sample recordings are not shipped
in Git or the production APK. `python -m unittest discover -s tools -p 'test_*.py'`
runs simulator checks.

## Release maintenance

See [Releasing](RELEASING.md) for signed APKs, merged firmware, checksums, private
key handling and GitHub release uploads. Tests on a connected phone/board are a
separate step from building; see [Testing](TESTING.md).
