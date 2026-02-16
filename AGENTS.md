# Middle agent guide

## purpose and scope
- This file is for coding agents working in this repository.
- Keep changes small, explicit, and consistent with existing code.
- This repository currently contains two code paths:
- `src/main.cpp`: ESP32-S3 firmware (Arduino via PlatformIO).
- `sync.py`: host-side BLE sync and transcription script (Python via `uv run --script`).

## repository facts discovered
- Build system: PlatformIO (`platformio.ini`).
- Firmware environment: `esp32-s3-devkitc-1`.
- Python runtime pattern: shebang with inline `uv` script metadata.
- No dedicated test suite is currently present.
- No `.cursor/rules/`, `.cursorrules`, or `.github/copilot-instructions.md` files were found.

## cursor and copilot rules
- Cursor rules: none found in this repository.
- Copilot instructions: none found in this repository.
- If these files are added later, treat them as authoritative and update this guide.

## build, lint, and test commands
- Run all commands from repo root: `/home/stavros/Code/Hardware/middle`.

## firmware commands (platformio)
- Install PlatformIO Core if missing: `pipx install platformio` or equivalent.
- Build firmware: `pio run -e esp32-s3-devkitc-1`.
- Build and upload firmware over USB: `pio run -e esp32-s3-devkitc-1 -t upload`.
- Open serial monitor: `pio device monitor -b 115200`.
- Upload filesystem image (LittleFS): `pio run -e esp32-s3-devkitc-1 -t uploadfs`.
- Clean build artifacts: `pio run -e esp32-s3-devkitc-1 -t clean`.
- Print detected serial devices: `pio device list`.

## static analysis and linting
- There is no configured formatter or linter config checked into this repo.
- Use PlatformIO static checks for firmware when needed:
- `pio check -e esp32-s3-devkitc-1`.
- If you add a formatter or linter, document exact commands here.

## python script commands
- Run sync client (uses inline dependencies via uv): `uv run sync.py`.
- Optional dry import check: `uv run python -c "import sync"`.
- If you add tests later, keep `uv` as the default runner for consistency.

## test commands
- Current state: no automated tests are present.
- Firmware test harness command (if tests are added under PlatformIO):
- Run all firmware tests: `pio test -e esp32-s3-devkitc-1`.
- Run a single firmware test by name: `pio test -e esp32-s3-devkitc-1 -f <test_name>`.
- Python test harness command (if `pytest` is added):
- Run all python tests: `uv run pytest`.
- Run a single test file: `uv run pytest tests/test_sync.py`.
- Run a single test case: `uv run pytest tests/test_sync.py::test_case_name`.

## style guidelines

## source of truth
- Firmware behavior is defined by code in `src/main.cpp`, not by `ARCHITECTURE.md`.
- Host sync behavior is defined by code in `sync.py`, not by TODO notes.
- Update docs when behavior changes, but do not change behavior to match stale docs.

## general
- Prefer clear, direct code over abstractions.
- Keep functions focused and small when possible.
- Avoid speculative infrastructure and unused helpers.
- Keep behavior deterministic and explicit.
- Preserve current protocol and storage compatibility unless a task requires migration.

## comments and documentation
- Keep comments minimal and only for non-obvious intent.
- Prefer comments that explain why a choice was made.
- Remove comments that restate code line-by-line.
- When changing commands or workflows, update this file in the same change.

## naming
- Use descriptive names with domain meaning.
- Python: `snake_case` for functions and variables, `UPPER_SNAKE_CASE` for constants.
- C++ (this repo): keep existing local style (`snake_case` functions and variables, lower-case local class names currently used in `main.cpp`).
- BLE UUID constants should remain explicit and grouped.

## imports and includes
- Python imports belong at file top-level.
- Group Python imports as: standard library, third-party, local (if any).
- C++ includes belong at top of file.
- Do not add lazy imports inside functions unless there is a measured startup reason.

## typing and interfaces
- Python: add type annotations for public functions and new helpers.
- Preserve existing typed return signatures such as `tuple[int, list[Path]]`.
- Prefer concrete built-in collection types (`list`, `dict`, `tuple`).
- C++: use explicit fixed-width types where protocol size matters (`uint8_t`, `uint16_t`, `uint32_t`).

## formatting
- Match existing formatting in each file.
- Python: follow PEP 8 style and keep line lengths readable.
- C++: keep brace and spacing style consistent with `src/main.cpp`.
- Keep logs concise and prefixed by subsystem tags already in use (for example `[ble]`, `[rec]`, `[flash]`).
- Serial output must use `\r\n` line endings (e.g. `Serial.printf("...\r\n")` and `Serial.println()` — note that `println` already appends `\r\n`).

## error handling
- Do not silently swallow errors.
- Fail fast for invariant violations.
- For device and BLE operations, log actionable context before returning.
- Keep recovery paths simple and explicit.
- Avoid broad exception handling unless boundary code needs it.

## firmware-specific conventions
- Maintain current sample rate and file format unless intentionally changing protocol.
- Recording storage format is raw unsigned PCM8 and is consumed by `sync.py`.
- Keep command constants synchronized with the companion script.
- Any BLE command or characteristic change requires coordinated update in both files.
- Preserve LittleFS path normalization behavior.
- Keep memory allocation checks in recording paths.
- Be careful with timing loops and blocking delays, since they affect sample quality and transfer throughput.

## sync client conventions
- Keep BLE service and characteristic UUIDs in one constant section.
- Keep conversion pipeline explicit: PCM8 -> PCM16LE -> MP3.
- Transcription is optional workflow layered on top of successful sync.
- Save outputs under `recordings/` with timestamped filenames.
- Log transfer progress for long operations.

## architecture and docs alignment
- If implementation changes behavior, update `ARCHITECTURE.md` in the same change.
- Keep protocol docs and actual chunk sizes consistent.
- Avoid stale comments that describe behavior no longer in code.

## version control expectations
- Keep diffs focused; avoid unrelated drive-by changes.
- Do not reformat entire files unless asked.
- Preserve user changes in a dirty tree unless explicitly told to revert them.
- Do not commit build artifacts under `.pio/` or generated recordings.

## change management for agents
- Before editing, read the relevant section in `src/main.cpp` or `sync.py` fully.
- Prefer minimal diffs over broad rewrites.
- Do not rename files or symbols without clear benefit.
- Do not introduce new dependencies unless required by the task.
- If introducing a dependency, document why and how to run affected commands.

## validation checklist before finishing
- Firmware compiles: `pio run -e esp32-s3-devkitc-1`.
- If firmware protocol touched, verify matching constants in `sync.py`.
- If python code touched, run `uv run python -m py_compile sync.py`.
- If tests exist for touched area, run them.
- Confirm docs updated when behavior changes.

## notes for future improvements
- Add a real lint stack (`ruff` for Python, `clang-format` or `clang-tidy` for C++).
- Add automated tests for sync protocol parsing and audio conversion.
- Add a minimal hardware-in-the-loop smoke test script for upload and BLE handshake.
