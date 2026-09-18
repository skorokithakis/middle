# Middle

Middle is a small, rechargeable, thought-recording pendant. It's inspired by the [Pebble
Index 01](https://repebble.com/index), but I wanted it now, so I made it.

It uses a microphone and an ESP32 S3 to record your thoughts and transfer them to your
phone/computer/whatever for later processing.

There's an Android app and a Python script so you can transfer the files to the PC.

The Index 01 did eventually arrive, so the Android app now syncs from one of those as well.
You pick which device you want in the settings, one at a time, and everything after that is
the same for both: the recording gets stored, transcribed if you've asked for that, and
posted to your webhook.

## Features

* Press a button to record, release to stop.
* Bluetooth syncing happens after a recording, or tap the button quickly to force a BT wakeup/sync.
* Supports long audio files (probably many minutes).
* Transferring files to the PC is pretty fast.
* Optionally transcribes the audio files using OpenAI's GPT 4o transcribe.
* Light on battery life, since it consumes no power when not in use.
* Privacy-preserving, no connections anywhere, no nothing.
* The Android app also syncs from a real Pebble Index 01, if you happen to have one.


## Hardware

I used:

* An ESP32 S3, in a micro board with a battery charge circuit.
* An INMP441 I2S microphone board.
* A button.
* A small LiPo battery.

The mic wants four pins: SCK on 6, WS on 5, SD on 7, and its power on 9, so the firmware
can cut power to it during deep sleep. The button goes between pin 2 and GND, since that's
the pin the chip wakes up on. Battery voltage is read on pin 3 through a divider.

Flash the firmware and use the provided Python script to transfer the files from the
pendant. Done.


## Index 01 support

The Android app can sync from an actual Index 01 instead of the pendant. Switch the device
over in the settings, then pick your ring out of the list of Bluetooth devices your phone
has already paired with. There's no pairing flow in the app, because the ring bonds to the
phone rather than to an app, so if the official app has already paired it then it's there
waiting for you.

The ring's protocol is closed, so the app leans on Core Devices' own `haversine` library,
which is published on Maven Central under Apache 2.0. Nothing is copied out of their app
itself, which is GPL, and this repo has no licence at all.

Three things worth knowing. The ring never deletes anything, so the first sync pulls down
its whole backlog, which takes a few minutes and produces a pile of recordings you probably
don't want. It records at 9997Hz, which is not a sample rate AAC can represent, so the app
resamples to 16000Hz before encoding: Android's encoder doesn't complain about the odd rate,
it just quietly writes 44100 into the file instead and plays everything back four and a half
times too fast. And the ring stores about five minutes of audio, so it's worth syncing
before you go away for a week.

Only the Android app talks to the ring. The Python script is pendant-only, and I have no
plans to change that.
