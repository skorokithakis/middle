---
id: mid-tg74
status: closed
deps: []
links: []
created: 2026-03-26T02:25:46Z
type: task
priority: 2
assignee: Stavros Korokithakis
---
# Add erase-pairing command to firmware

Add command_erase_pair_token = 0x06 to the firmware command constants. In the command processing loop (behind the existing connection_authenticated gate), handle it by: 1) erasing the pair_token key from NVS (add a small nvs_erase_pair_token helper, similar to the existing nvs_write_pair_token), 2) deleting all recording files from LittleFS (iterate and remove), 3) disconnecting the client. Log each step with appropriate subsystem tags ([ble], [flash]).

## Acceptance Criteria

Firmware compiles with pio run. New command 0x06 is defined and handled in the command loop.

