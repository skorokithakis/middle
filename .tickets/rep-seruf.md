---
id: rep-seruf
status: closed
deps: []
links: []
created: 2026-09-22T22:57:08Z
type: task
priority: 2
assignee: Stavros Korokithakis
---
# Fake call speaks a message with TTS

ready for implementation

Objective: when a FAKE_CALL is answered, speak a per-action message through android.speech.tts.TextToSpeech as call audio, looping until the call ends.

Scope:
- Action.kt: new field message: String = "" (JSON key 'message'; absent key reads as "", present non-string is malformed like the other string fields). Extend ActionTest round-trip.
- ActionsViewModel / ActionsScreen: new FAKE_CALL actions (voice list and click slot) default message to a few filler lines, e.g. "Hey, it's me.\nSorry to bother you, do you have a minute?\nSomething came up and I need you to call me back as soon as you can." Multi-line text field 'Message' in FakeCallFields (so both voice cards and click slot get it). Put default text and label in strings.xml.
- ActionRunner.runFakeCall: pass message via a new FakeCallAccount extra.
- FakeCallConnectionService/FakeCallConnection: create TextToSpeech when the connection is created (ringing) so it is ready on answer; set AudioAttributes USAGE_VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH. On answer: wait ~1 s, speak non-blank lines in order with ~2 s silence between lines, ~4 s after the last, then repeat until disconnect. Use QUEUE_ADD with playSilentUtterance for pauses, or re-queue on UtteranceProgressListener.onDone; keep it simple. On any disconnect/destroy: stop and shutdown TTS, remove handler callbacks. Blank message or TTS init failure: stay silent, log with [action]/fake call tag.
- ARCHITECTURE.md: update fake call description and Actions screen row.

Non-goals: speaking transcript remainder, per-action voice/language/speed, cloud TTS, interactive caller, new dependencies.

Caveats: existing stored actions load with empty message (silent) on purpose. Update the FakeCallConnection KDoc that says there is no audio.

