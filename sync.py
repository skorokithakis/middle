#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.8"
# dependencies = [
#     "bleak",
#     "lameenc",
#     "openai",
#     "tqdm",
# ]
# ///
"""
BLE sync client for the Middle pendant.

Continuously scans for the pendant, connects when found, downloads all pending
recordings, saves them as WAV files, and acknowledges receipt so the pendant
can delete them from flash.

"""
import asyncio
import os
import struct
import time
from datetime import datetime
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
import lameenc
from openai import AuthenticationError, OpenAI, OpenAIError
from tqdm import tqdm

SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_FILE_COUNT_UUID = "19b10001-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_FILE_INFO_UUID = "19b10002-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_AUDIO_DATA_UUID = "19b10003-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_COMMAND_UUID = "19b10004-e8f2-537e-4f6c-d104768a1214"
CHARACTERISTIC_VOLTAGE_UUID = "19b10005-e8f2-537e-4f6c-d104768a1214"

COMMAND_REQUEST_NEXT = bytes([0x01])
COMMAND_ACK_RECEIVED = bytes([0x02])
COMMAND_SYNC_DONE = bytes([0x03])

SAMPLE_RATE = 16000
NUMBER_OF_CHANNELS = 1
# File header is a 4-byte little-endian uint32 sample count.
IMA_HEADER_SIZE = 4
MP3_BIT_RATE_KILOBITS_PER_SECOND = 64
TRANSCRIPTION_MODEL = "gpt-4o-transcribe"
OPENAI_API_KEY_ENV_NAME = "OPENAI_API_KEY"

RECORDINGS_DIRECTORY = Path(__file__).parent / "recordings"

# How often to scan for the pendant.
SCAN_INTERVAL_SECONDS = 3
MAX_FILE_TRANSFER_ATTEMPTS = 3
TRANSFER_STALL_TIMEOUT_SECONDS = 2.0
TRANSFER_TOTAL_TIMEOUT_SECONDS = 120.0

def log(message: str) -> None:
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{timestamp}] {message}")


ADPCM_STEP_TABLE = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37,
    41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173,
    190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
    724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
    7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500,
    20350, 22385, 24623, 27086, 29794, 32767,
]

ADPCM_INDEX_TABLE = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8]


def decode_ima_adpcm(data: bytes, sample_count: int) -> bytes:
    """Decode IMA ADPCM packed nibbles to signed 16-bit little-endian PCM.

    Each byte contains two nibbles (low nibble first). The decoder mirrors
    the encoder in the firmware exactly.
    """
    predicted_sample = 0
    step_index = 0
    output = bytearray(sample_count * 2)
    write_position = 0

    for i in range(sample_count):
        byte_index = i // 2
        if byte_index >= len(data):
            break

        if i % 2 == 0:
            nibble = data[byte_index] & 0x0F
        else:
            nibble = (data[byte_index] >> 4) & 0x0F

        step = ADPCM_STEP_TABLE[step_index]
        delta = step >> 3
        if nibble & 4:
            delta += step
        if nibble & 2:
            delta += step >> 1
        if nibble & 1:
            delta += step >> 2

        if nibble & 8:
            predicted_sample -= delta
        else:
            predicted_sample += delta

        predicted_sample = max(-32768, min(32767, predicted_sample))

        sample_le = predicted_sample & 0xFFFF
        output[write_position] = sample_le & 0xFF
        output[write_position + 1] = (sample_le >> 8) & 0xFF
        write_position += 2

        step_index += ADPCM_INDEX_TABLE[nibble]
        step_index = max(0, min(88, step_index))

    return bytes(output[:write_position])


def encode_mp3_from_ima(ima_data: bytes) -> bytes:
    """Decode an IMA ADPCM file (with 4-byte sample count header) to MP3."""
    sample_count = struct.unpack("<I", ima_data[:IMA_HEADER_SIZE])[0]
    adpcm_payload = ima_data[IMA_HEADER_SIZE:]
    pcm16 = decode_ima_adpcm(adpcm_payload, sample_count)

    encoder = lameenc.Encoder()
    encoder.set_bit_rate(MP3_BIT_RATE_KILOBITS_PER_SECOND)
    encoder.set_in_sample_rate(SAMPLE_RATE)
    encoder.set_channels(NUMBER_OF_CHANNELS)
    encoder.set_quality(2)

    return encoder.encode(pcm16) + encoder.flush()


def create_openai_client() -> OpenAI | None:
    api_key = os.getenv(OPENAI_API_KEY_ENV_NAME)
    if not api_key:
        log(
            f"Skipping transcription because {OPENAI_API_KEY_ENV_NAME} "
            "is not set."
        )
        return None

    return OpenAI(api_key=api_key)


def transcribe_mp3_file(openai_client: OpenAI, filepath: Path) -> Path:
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


async def sync_recordings(
    client: BleakClient,
    openai_client: OpenAI | None,
) -> tuple[int, list[Path]]:
    """Download all pending recordings from the pendant. Returns the number
    of files synced."""
    try:
        raw_voltage = await client.read_gatt_char(CHARACTERISTIC_VOLTAGE_UUID)
        millivolts = struct.unpack("<H", raw_voltage)[0]
        log(f"Battery: {millivolts / 1000:.2f}V ({millivolts} mV)")
    except BleakError:
        log("Voltage info unavailable (older firmware).")

    raw = await client.read_gatt_char(CHARACTERISTIC_FILE_COUNT_UUID)
    file_count = struct.unpack("<H", raw)[0]
    log(f"Pendant reports {file_count} pending recording(s).")

    if file_count == 0:
        return 0, []

    RECORDINGS_DIRECTORY.mkdir(parents=True, exist_ok=True)
    synced = 0
    saved_recordings: list[Path] = []

    skip_transcription = False

    for i in range(file_count):
        log(f"Requesting file {i + 1}/{file_count}...")

        audio_data = b""
        expected_size = 0
        chunk_count = 0
        transfer_elapsed = 0.0

        for attempt in range(1, MAX_FILE_TRANSFER_ATTEMPTS + 1):
            if attempt > 1:
                log(
                    f"Retrying file {i + 1}/{file_count} "
                    f"(attempt {attempt}/{MAX_FILE_TRANSFER_ATTEMPTS})."
                )

            received_chunks: list[bytearray] = []
            chunk_received = asyncio.Event()
            expected_size = 0
            chunk_count = 0
            received_total_bytes = 0
            transfer_progress = None

            def on_audio_data(_sender: int, data: bytearray) -> None:
                nonlocal chunk_count
                nonlocal received_total_bytes
                received_chunks.append(data)
                chunk_count += 1
                received_total_bytes += len(data)
                if transfer_progress is not None and expected_size > 0:
                    target_bytes = min(received_total_bytes, expected_size)
                    delta = target_bytes - transfer_progress.n
                    if delta > 0:
                        transfer_progress.update(delta)
                chunk_received.set()

            await client.start_notify(
                CHARACTERISTIC_AUDIO_DATA_UUID, on_audio_data
            )

            transfer_start = time.monotonic()
            try:
                log("Sending REQUEST_NEXT command.")
                await client.write_gatt_char(
                    CHARACTERISTIC_COMMAND_UUID, COMMAND_REQUEST_NEXT
                )

                # The pendant sets file info after receiving REQUEST_NEXT,
                # before streaming. Give it a moment to update the value.
                await asyncio.sleep(0.1)
                raw = await client.read_gatt_char(CHARACTERISTIC_FILE_INFO_UUID)
                expected_size = struct.unpack("<I", raw)[0]

                # Empty files are corrupt or aborted recordings. Skip them
                # immediately rather than retrying.
                if expected_size == 0:
                    log(f"File {i + 1}/{file_count} is empty, skipping.")
                    break

                # The file contains a 4-byte header plus ADPCM nibbles, so
                # we can't directly divide by sample rate for duration.
                # We'll compute it after decoding. Show raw size for now.
                adpcm_sample_count = (expected_size - IMA_HEADER_SIZE) * 2
                duration_seconds = adpcm_sample_count / SAMPLE_RATE
                log(
                    f"File size: {expected_size} bytes "
                    f"({duration_seconds:.1f}s of audio)."
                )
                transfer_progress = tqdm(
                    total=expected_size,
                    desc=f"File {i + 1}/{file_count}",
                    unit="B",
                    unit_scale=True,
                    leave=False,
                )
                if received_total_bytes > 0:
                    transfer_progress.update(min(received_total_bytes, expected_size))

                while received_total_bytes < expected_size:
                    elapsed = time.monotonic() - transfer_start
                    remaining_total = TRANSFER_TOTAL_TIMEOUT_SECONDS - elapsed
                    if remaining_total <= 0:
                        raise TimeoutError("Transfer exceeded total timeout.")

                    chunk_received.clear()
                    await asyncio.wait_for(
                        chunk_received.wait(),
                        timeout=min(
                            TRANSFER_STALL_TIMEOUT_SECONDS,
                            remaining_total,
                        ),
                    )
            except TimeoutError as error:
                log(
                    f"Transfer stalled at {received_total_bytes}/{expected_size} "
                    f"bytes ({error})."
                )
            finally:
                await client.stop_notify(CHARACTERISTIC_AUDIO_DATA_UUID)
                if transfer_progress is not None:
                    transfer_progress.close()

            if received_total_bytes >= expected_size and expected_size > 0:
                transfer_elapsed = time.monotonic() - transfer_start
                audio_data = b"".join(received_chunks)
                audio_data = audio_data[:expected_size]
                break

        # Handle empty files: ACK to delete from pendant and continue.
        if expected_size == 0:
            await client.write_gatt_char(
                CHARACTERISTIC_COMMAND_UUID, COMMAND_ACK_RECEIVED
            )
            synced += 1
            continue

        if len(audio_data) != expected_size:
            raise RuntimeError(
                f"Failed to transfer file {i + 1}/{file_count} after "
                f"{MAX_FILE_TRANSFER_ATTEMPTS} attempts."
            )

        speed = len(audio_data) / transfer_elapsed / 1024 if transfer_elapsed > 0 else 0
        log(
            f"Transfer complete: {chunk_count} chunks, "
            f"{len(audio_data)} bytes in {transfer_elapsed:.2f}s "
            f"({speed:.1f} KB/s)."
        )

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"recording_{timestamp}_{i}.mp3"
        filepath = RECORDINGS_DIRECTORY / filename

        mp3_data = encode_mp3_from_ima(audio_data)
        filepath.write_bytes(mp3_data)
        log(
            f"Saved {filepath} (MP3 {len(mp3_data)} bytes from "
            f"IMA ADPCM {len(audio_data)} bytes)."
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

        if openai_client is None or skip_transcription:
            continue

        log(f"Transcribing {filepath.name} with {TRANSCRIPTION_MODEL}...")
        try:
            transcript_path = transcribe_mp3_file(openai_client, filepath)
            log(f"Saved transcript: {transcript_path}")
        except AuthenticationError:
            log("Skipping remaining transcriptions: invalid API key.")
            skip_transcription = True
        except OpenAIError as error:
            log(f"Transcription failed for {filepath.name}: {error}")
            skip_transcription = True

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

        saved_recordings: list[Path] = []
        openai_client = create_openai_client()
        try:
            async with BleakClient(device, timeout=10) as client:
                log(f"Connected (MTU: {client.mtu_size}).")
                synced, saved_recordings = await sync_recordings(
                    client,
                    openai_client,
                )
                log(f"Sync complete, {synced} file(s) transferred.")
        except TimeoutError:
            log("Connection attempt timed out. Pendant likely returned to sleep.")
            log("Resuming scan.")
            continue
        except BleakError as error:
            log(f"BLE connection failed: {error}")
            log("Resuming scan.")
            continue

        log("Disconnected, resuming scan.\n")
        scan_count = 0


if __name__ == "__main__":
    asyncio.run(main())
