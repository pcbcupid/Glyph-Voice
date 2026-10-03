# Install once, then just start Glyph Voice

No Arduino IDE, Android Studio, Git, global Python or global Node is required.
Download the project ZIP from your instructor or [GitHub → Code → Download ZIP](https://github.com/pcbcupid/Glyph-Voice).
**Extract the entire ZIP** into a writable local folder before running anything.
Keep that folder: the tools, model and server live inside it.

## One command for your computer

Open a terminal **in the extracted project folder**:

| Computer | Install once (online) | Start each time |
| --- | --- | --- |
| Linux x86_64, glibc (e.g. recent Ubuntu) | `bash installation/install.sh` | `bash start-web.sh` |
| Windows 10/11 x64, Command Prompt | `installation\install.cmd` | `start-web.cmd` |
| macOS Intel / Apple Silicon | `bash installation/install-macos.command` | `bash start-web.sh` |

Windows: double-clicking `installation\install.cmd` also works. PowerShell:
`.\installation\install.cmd`. macOS: run the command in Terminal; Finder can
also open the `.command` file if its executable bit was preserved. `bash start.sh`
is a short alias for the same existing Unix launcher.

Allow **at least 5 GB free storage** for tools, packages, caches and the model.
The model download is about **501 MB**, extracting to **663 MB**. Plan for 4–6 GB
available RAM during inference; speed depends on the computer. Run one installer
at a time and prepare computers before the workshop, not all on the room Wi-Fi.

The installer downloads/verifies pinned **uv 0.12.22**, privately installs
**Python 3.13**, downloads/verifies **Node 24.21.0** with npm, installs the pinned
Python and locked web dependencies, builds React, then downloads/verifies/extracts
**Parakeet Unified English 0.6B INT8 streaming 1120ms**. Existing matching project
environments/models are reused. It prints **Installation complete** and exits;
no background daemon starts until you run start-web.

Everything stays in this project (`.tools/`, `web/node_modules`, `web/dist`). No
sudo/admin, system PATH/profile/registry changes, Arduino, firmware compilers or
Android tools. Windows uses a process-only PowerShell execution-policy override.
Read the scripts before running them. uv supplies its [managed Python distribution](https://docs.astral.sh/uv/concepts/python-versions/).
The project and model have separate [licenses](../THIRD_PARTY_NOTICES.md).

## Start and connect

Run the start command above and leave the terminal open. It opens
**http://localhost:8765**. Choose **Local model**, paste the private server token
printed in the terminal, choose the installed model folder and load it. Wait for
model warm-up and Glyph connected before pressing BOOT. Full instructions:
[Workshop guide](../docs/WORKSHOP.md).

Parakeet runs on **this computer**, not inside an iPhone browser. No internet is
needed after preparation for local transcription; keep local Wi-Fi available.
Cloud summaries/optional cloud STT need separate provider setup and internet.
One server supports one active recording. Keep the browser visible and computer
awake. Press **Ctrl+C** in the launcher terminal to stop the server.

Offline launch with no package installations:

```sh
bash start-web.sh --no-install
```

Windows: `start-web.cmd --no-install`. Refreshing clears tab-only credentials,
not committed browser transcript history. A server restart generates a new token.

## Already have an extracted model?

Use a trusted compatible model collection (see [model support](../server/README.md)):

```sh
bash installation/install.sh --models-dir "/path/to/my models"
```

macOS: same flags on the `.command` entry point. Windows:
`installation\install.cmd --models-dir "C:\Glyph\models"`.
`--skip-model` installs software only; provide a model collection before launching.
The picker browses the **server computer's** model folder, not the phone filesystem.
Without an explicit `--models-dir`, rerunning installation preserves a previous
selected model path. It does not delete models, histories or credentials.

For a workshop, prepare the verified extracted model and its license/notices once
and copy that collection to participant computers. Don't copy virtual environments
between OSes. Run the installer on each machine; never share API keys/server tokens.

## Troubleshooting

- Network/proxy/checksum error: restore access to the named URL and rerun. Never
  disable TLS or checksum validation. Interrupted downloads/extractions are staged.
- Linux needs basic `curl`, `tar` and `sha256sum`/`shasum`; install missing OS
  utilities with your package manager. The script intentionally does not use sudo.
- Windows policy/antivirus/DLL errors: consult the administrator. Native Python
  packages may need Microsoft's supported Visual C++ x64 runtime on some PCs;
  don't obtain individual DLLs from random sites.
- Permission denied/no space: extract into a writable local folder, not Program
  Files or inside the ZIP. Avoid synced/network drives; free space and rerun.
- Incomplete existing runtime/model folder: rename the **exact** folder reported
  and rerun. Unknown files are not overwritten automatically.
- Supported: Linux x86_64 glibc, Windows x64, modern macOS Intel/ARM64. Not supported
  by these installers: Windows ARM, Alpine/musl, 32-bit PCs, iOS/Android backend hosts.
- Moved the project: Python environments contain absolute paths. Move it back,
  or install in a fresh extraction and reuse the model with `--models-dir`.
  Keep the old folder until the new one works.
- Port occupied: stop the old launcher or use `--port 8766`. Browser history is
  origin-specific, so a different port has a different history.

No installer can guarantee compatibility with every OS policy/network. Native
Windows/macOS execution must be rehearsed on the workshop computers in addition
to Linux execution and cross-platform unit checks.
