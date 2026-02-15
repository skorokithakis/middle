#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.8"
# dependencies = [
#     "bleak",
#     "lameenc",
#     "openai",
# ]
# ///
"""
BLE sync client for the Middle pendant.

Continuously scans for the pendant, connects when found, downloads all pending
recordings, saves them as WAV files, and acknowledges receipt so the pendant
can delete them from flash.

"""
import asyncio
import struct
import time
from datetime import datetime
from pathlib import Path

from bleak import BleakClient, BleakScanner
import lameenc
from openai import OpenAI

SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_FILE_COUNT_UUID = "19b10001-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_FILE_INFO_UUID = "19b10002-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_AUDIO_DATA_UUID = "19b10003-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_COMMAND_UUID = "19b10004-e8f2-537e-4f6c-d104768a1214"

COMMAND_REQUEST_NEXT = bytes([0x01])
COMMAND_ACK_RECEIVED = bytes([0x02])
COMMAND_SYNC_DONE = bytes([0x03])

SAMPLE_RATE = 16000
BITS_PER_SAMPLE = 8
NUMBER_OF_CHANNELS = 1
MP3_BIT_RATE_KILOBITS_PER_SECOND = 64
TRANSCRIPTION_MODEL = "gpt-4o-transcribe"

RECORDINGS_DIRECTORY = Path(__file__).parent / "recordings"

# How often to scan for the pendant.
SCAN_INTERVAL_SECONDS = 3

openai_client = OpenAI()


def log(message: str) -> None:
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{timestamp}] {message}")


def unsigned_pcm8_to_signed_pcm16le(pcm8: bytes) -> bytes:
    """Convert unsigned 8-bit PCM to signed 16-bit little-endian PCM."""
    pcm16 = bytearray(len(pcm8) * 2)
    write_index = 0
    for sample in pcm8:
        signed_sample = (sample - 128) << 8
        pcm16[write_index] = signed_sample & 0xFF
        pcm16[write_index + 1] = (signed_sample >> 8) & 0xFF
        write_index += 2
    return bytes(pcm16)


def encode_mp3_from_pcm8(pcm8: bytes) -> bytes:
    """Encode 8-bit unsigned mono PCM (16kHz) to MP3 bytes."""
    pcm16 = unsigned_pcm8_to_signed_pcm16le(pcm8)

    encoder = lameenc.Encoder()
    encoder.set_bit_rate(MP3_BIT_RATE_KILOBITS_PER_SECOND)
    encoder.set_in_sample_rate(SAMPLE_RATE)
    encoder.set_channels(NUMBER_OF_CHANNELS)
    encoder.set_quality(2)

    return encoder.encode(pcm16) + encoder.flush()


def transcribe_mp3_file(filepath: Path) -> Path:
    """Transcribe an MP3 file with GPT-4o Transcribe and save transcript."""
    with filepath.open("rb") as audio_file:
        transcript_text = openai_client.audio.transcriptions.create(
            model=TRANSCRIPTION_MODEL,
            file=audio_file,
            response_format="text",
        )

    transcript_path = filepath.with_suffix(".txt")
    transcript_path.write_text(transcript_text)

    print("----- transcript start -----")
    print(transcript_text)
    print("----- transcript end -------")

    return transcript_path


async def sync_recordings(client: BleakClient) -> tuple[int, list[Path]]:
    """Download all pending recordings from the pendant. Returns the number
    of files synced."""
    raw = await client.read_gatt_char(CHARACTERISTIC_FILE_COUNT_UUID)
    file_count = struct.unpack("<H", raw)[0]
    log(f"Pendant reports {file_count} pending recording(s).")

    if file_count == 0:
        return 0, []

    RECORDINGS_DIRECTORY.mkdir(parents=True, exist_ok=True)
    synced = 0
    saved_recordings: list[Path] = []

    for i in range(file_count):
        log(f"Requesting file {i + 1}/{file_count}...")

        # Accumulate notification chunks here.
        received_chunks: list[bytearray] = []
        transfer_complete = asyncio.Event()
        expected_size = 0
        chunk_count = 0

        def on_audio_data(_sender: int, data: bytearray) -> None:
            nonlocal chunk_count
            received_chunks.append(data)
            chunk_count += 1
            total = sum(len(chunk) for chunk in received_chunks)
            if chunk_count % 20 == 0:
                log(f"  Received {chunk_count} chunks, {total} bytes so far...")
            if expected_size > 0 and total >= expected_size:
                transfer_complete.set()

        await client.start_notify(
            CHARACTERISTIC_AUDIO_DATA_UUID, on_audio_data
        )

        log("Sending REQUEST_NEXT command.")
        await client.write_gatt_char(
            CHARACTERISTIC_COMMAND_UUID, COMMAND_REQUEST_NEXT
        )

        # The pendant sets file info after receiving REQUEST_NEXT, before
        # streaming. Give it a moment to update the characteristic.
        await asyncio.sleep(0.1)
        raw = await client.read_gatt_char(CHARACTERISTIC_FILE_INFO_UUID)
        expected_size = struct.unpack("<I", raw)[0]
        duration_seconds = expected_size / SAMPLE_RATE
        log(
            f"File size: {expected_size} bytes "
            f"({duration_seconds:.1f}s of audio)."
        )

        # Check if we already received enough data before we read the size.
        total = sum(len(chunk) for chunk in received_chunks)
        if total >= expected_size:
            transfer_complete.set()

        # Wait for all chunks. The pendant sends 500-byte chunks with 20ms
        # gaps, so a 960KB file takes ~40 seconds. Use a generous timeout.
        transfer_start = time.monotonic()
        await asyncio.wait_for(transfer_complete.wait(), timeout=120)
        transfer_elapsed = time.monotonic() - transfer_start
        await client.stop_notify(CHARACTERISTIC_AUDIO_DATA_UUID)

        audio_data = b"".join(received_chunks)
        # Truncate to expected size in case the last chunk had padding.
        audio_data = audio_data[:expected_size]

        speed = len(audio_data) / transfer_elapsed / 1024 if transfer_elapsed > 0 else 0
        log(
            f"Transfer complete: {chunk_count} chunks, "
            f"{len(audio_data)} bytes in {transfer_elapsed:.2f}s "
            f"({speed:.1f} KB/s)."
        )

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"recording_{timestamp}_{i}.mp3"
        filepath = RECORDINGS_DIRECTORY / filename

        mp3_data = encode_mp3_from_pcm8(audio_data)
        filepath.write_bytes(mp3_data)
        log(
            f"Saved {filepath} (MP3 {len(mp3_data)} bytes from "
            f"PCM {len(audio_data)} bytes)."
        )
        saved_recordings.append(filepath)

        log("Sending ACK_RECEIVED command.")
        try:
            await client.write_gatt_char(
                CHARACTERISTIC_COMMAND_UUID, COMMAND_ACK_RECEIVED
            )
            log("ACK write succeeded.")
        except Exception as error:
            log(f"ACK write failed ({error}).")
            return synced, saved_recordings
        synced += 1

    log("Sending SYNC_DONE command.")
    try:
        await client.write_gatt_char(
            CHARACTERISTIC_COMMAND_UUID, COMMAND_SYNC_DONE
        )
    except Exception as error:
        log(f"SYNC_DONE write failed ({error}).")
    return synced, saved_recordings


async def main() -> None:
    log("Middle BLE sync client started.")
    log(f"Recordings will be saved to: {RECORDINGS_DIRECTORY}")
    log(f"Scanning for pendant (service {SERVICE_UUID})...")

    scan_count = 0
    while True:
        scan_count += 1
        devices = await BleakScanner.discover(
            timeout=SCAN_INTERVAL_SECONDS,
            service_uuids=[SERVICE_UUID],
        )

        if not devices:
            if scan_count % 10 == 0:
                log(f"Still scanning... ({scan_count} scans, no pendant found)")
            continue

        device = devices[0]
        log(f"Found pendant: {device.name} ({device.address}).")
        log("Connecting...")

        async with BleakClient(device, timeout=10) as client:
            log(f"Connected (MTU: {client.mtu_size}).")
            synced, saved_recordings = await sync_recordings(client)
            log(f"Sync complete, {synced} file(s) transferred.")

        if len(saved_recordings) > 0:
            log(
                f"Transcribing {len(saved_recordings)} recording(s) "
                f"with {TRANSCRIPTION_MODEL}..."
            )
            for filepath in saved_recordings:
                transcript_path = transcribe_mp3_file(filepath)
                log(f"Saved transcript: {transcript_path}")

        log("Disconnected, resuming scan.\n")
        scan_count = 0


if __name__ == "__main__":
    asyncio.run(main())
