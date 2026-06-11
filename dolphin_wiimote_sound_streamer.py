#!/usr/bin/env python3
"""
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
           Wii Controller - Dolphin Wiimote Sound Streamer Helper
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
This Python helper script captures your PC audio and streams it to your Android device
running the Wii Controller app. This brings immersive speaker sounds (like Wii Menu clicks,
game-specific local indicators, character selectors) directly to your hands!

Requirements:
    - python3
    - pip install sounddevice numpy

Usage:
    python dolphin_wiimote_sound_streamer.py --ip <YOUR_PHONE_IP> --port 26761
"""

import sys
import time
import socket
import argparse

# Check requirements and import
try:
    import numpy as np
    import sounddevice as sd
except ImportError:
    print("\n[!] PySoundDevice and NumPy are required to capture live speaker outputs.")
    print("    Please install them using pip:")
    print("    pip install sounddevice numpy")
    print("\n    Launching fallback SIMULATION mode (streams retro Wii-style chime alerts to test the phone server)...\n")
    sd = None

def run_live_stream(phone_ip, port):
    print(f"[*] Connecting UDP Audio stream to phone: {phone_ip}:{port}")
    print("[*] Recording raw system speakers (loopback mode) at low-latency...")

    # Set up UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # 11025Hz sampling rate mono 16-bit PCM (Wii Speaker specification)
    target_fs = 11025
    block_size = 512 # small chunks for low-latency (< 40ms)

    # Check available audio loopback devices
    devices = sd.query_devices()
    loopback_dev = None

    # Try to auto-select loopback or stereo mix
    print("\nAvailable Audio Devices:")
    for idx, d in enumerate(devices):
        print(f"  [{idx}] {d['name']} - Inputs: {d['max_input_channels']}, Outputs: {d['max_output_channels']}")
        # Windows Loopback / WASAPI, Linux pulse, or macOS soundflower/blackhole
        d_name_lower = d['name'].lower()
        if d['max_input_channels'] > 0 and ("loopback" in d_name_lower or "stereo mix" in d_name_lower or "monitor" in d_name_lower or "wasapi" in d_name_lower):
            loopback_dev = idx

    selected_dev = loopback_dev if loopback_dev is not None else sd.default.device[0]
    print(f"\n[*] Selected device index: {selected_dev} for audio capture.")

    def callback(indata, frames, time, status):
        if status:
            print(f"Status: {status}", file=sys.stderr)
        
        # Convert floating point recorded buffers back to 16-bit PCM bytes
        # take channel 0 (mono)
        pcm_data = (indata[:, 0] * 32767).astype(np.int16)
        payload = pcm_data.tobytes()
        
        # Stream over low latency UDP
        try:
            sock.sendto(payload, (phone_ip, port))
        except Exception as e:
            pass

    try:
        # sounddevice stream listener
        with sd.InputStream(device=selected_dev, channels=1, samplerate=target_fs, blocksize=block_size, callback=callback):
            print("\n==========================================================")
            print(" [ACTIVE] SOUND STREAMING TO PHONE SPEAKERS IS LIVE!")
            print(" Press Ctrl+C to terminate the streamer.")
            print("==========================================================")
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Streamer terminated by user.")
    except Exception as e:
        print(f"\n[!] Audio recording failure: {e}")
        print("Note: On Windows, ensure you select a WASAPI loopback device.")

def run_simulation_stream(phone_ip, port):
    """
    Simulation fallback that doesn't need external libraries.
    Generates a retro chime pulse and streams it to test connection and haptics.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    fs = 11025
    duration = 0.25 # quarter second chime
    t = [i / fs for i in range(int(fs * duration))]
    
    # Simple retro chime combo wave
    freq1 = 880 # A5
    freq2 = 1320 # E6
    bounce = []
    for step in t:
        v = 0.5 * (32767 * 0.5 * (1.0 - (step/duration))) # fading amplitude
        sample = v * (0.6 * 0.0 + 0.4 * 0.0) # sine waves calculated below
        bounce.append(sample)

    print(f"[*] Pinging simulation pulses to {phone_ip}:{port}...")
    print("[*] Press Ctrl+C to stop the test chime loop.")
    
    try:
        cycle = 0
        while True:
            cycle += 1
            print(f" -> Pulse chime #{cycle}")
            
            # Generate tone array in int16
            pcm_samples = []
            freq = 440 if cycle % 2 == 0 else 880
            for step in range(int(fs * 0.2)): // 200ms tone
                envelope = 1.0 - (step / (fs * 0.2))
                val = int(envelope * 16383 * (np.sin(2 * np.pi * freq * (step / fs)) if 'np' in globals() else (step % 20 - 10) * 100))
                pcm_samples.append(val)
                
            # Convert list to signed short little endian bytes
            import struct
            payload = struct.pack(f'<{len(pcm_samples)}h', *pcm_samples)
            
            # Send chunks
            chunk_size = 512
            for i in range(0, len(payload), chunk_size):
                chunk = payload[i:i+chunk_size]
                sock.sendto(chunk, (phone_ip, port))
                time.sleep(0.01)
                
            time.sleep(2.0)
    except KeyboardInterrupt:
        print("\n[*] Simulation shut down.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Dolphin Wiimote loopback speaker sound streamer.")
    parser.add_argument("--ip", help="Android Phone IP Address shown in the app", required=True)
    parser.add_argument("--port", type=int, default=26761, help="Wii sound receiver port (default: 26761)")
    args = parser.parse_args()

    if sd is not None:
        run_live_stream(args.ip, args.port)
    else:
        run_simulation_stream(args.ip, args.port)
