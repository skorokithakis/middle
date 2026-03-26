---
id: mid-tvit
status: closed
deps: [mid-tg74]
links: []
created: 2026-03-26T02:25:52Z
type: task
priority: 2
assignee: Stavros Korokithakis
---
# Add --reset flag to sync.py

Add a --reset CLI flag to sync.py. Like --bootloader, it requires --token for authentication. Flow: connect, authenticate, sync all recordings (existing sync_recordings call), then send the new COMMAND_ERASE_PAIRING (0x06) command, log that the pendant has been reset, and exit. Add COMMAND_ERASE_PAIRING = bytes([0x06]) to the command constants. The flag is mutually exclusive with --bootloader (error if both provided). Validate that --token is provided when --reset is used (exit with error if not).

## Acceptance Criteria

uv run python -m py_compile sync.py passes. --reset flag is available and properly validated.

