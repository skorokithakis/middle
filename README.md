# Middle

Middle is a small, rechargeable, thought-recording pendant. It's inspired by the [Pebble
Index 01](https://repebble.com/index), but I wanted it now, so I made it.

It uses a microphone and an ESP32 S3 to record your thoughts and transfer them to your
phone/computer/whatever for later processing.

## Hardware

I used:

* An ESP32 S3, in a micro board with a battery charge circuit.
* A MAX 9814 mic board with AGC I had lying around.
* A button.
* A small LiPo battery.

Hook them all up, the mic goes to pin 9, the button goes between pin 12 and GND.

Flash the firmware and use the provided Python script to transfer the files from the
pendant. Done.
