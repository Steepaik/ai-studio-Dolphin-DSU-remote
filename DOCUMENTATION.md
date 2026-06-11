# Wii Controller: Architecture & Advanced Systems Documentation

This document provides a highly detailed, byte-level architectural breakdown of the **Wii Controller** Android application-emulator bridge. It details the system workflows, communication protocols, sensor scaling mathematics, audio streams, and emulator bindings that connect Android devices to the **Dolphin Emulator** for immersive, low-latency, and multi-player gaming.

---

## Table of Contents
1. [System Architectural Overview](#1-system-architectural-overview)
2. [Cemuhook DSU (DualShock UDP) Server Protocol](#2-cemuhook-dsu-dualshock-udp-server-protocol)
    * [Socket and Event Loop](#socket-and-event-loop)
    * [Standard Packet Frame Layout](#standard-packet-frame-layout)
    * [Message Transaction Protocol](#message-transaction-protocol)
    * [Multiplayer Slot Management Grid (P1 - P4)](#multiplayer-slot-management-grid-p1---p4)
3. [Wireless Bluetooth Sync Protocol (Sender / Receiver)](#3-wireless-bluetooth-sync-protocol-sender--receiver)
    * [RFCOMM Connection Topology](#rfcomm-connection-topology)
    * [Binary Serialization Format](#binary-serialization-format)
    * [Dynamic Slot Assignment Algorithm](#dynamic-slot-assignment-algorithm)
4. [Wii MotionPlus Precision Sensors Engine](#4-wii-motionplus-precision-sensors-engine)
    * [Calibration Bias Equation](#calibration-bias-equation)
    * [Sensor Conversions and Scaling](#sensor-conversions-and-scaling)
    * [Shake Simulation Controller](#shake-simulation-controller)
5. [In-Hand Real-Time UDP Speaker Streamer](#5-in-hand-real-time-udp-speaker-streamer)
    * [PCM Specification and Codecs](#pcm-specification-and-codecs)
    * [Android `AudioTrack` Playback Pipeline](#android-audiotrack-playback-pipeline)
    * [Desktop Python Host Sound Loopback Wrapper](#desktop-python-host-sound-loopback-wrapper)
6. [Dolphin Android Emulator Integration](#6-dolphin-android-emulator-integration)
7. [Database Persistence & Diagnostics Telemetry](#7-database-persistence--diagnostics-telemetry)

---

## 1. System Architectural Overview

The Wii Controller application adopts an **MVVM (Model-View-ViewModel)** structural paradigm engineered with modern **Jetpack Compose** and **Kotlin Coroutines**. The app fulfills three concurrent network and hardware roles, functioning as:
1. **A Primary Controller**: Captures local device buttons, analog stick state, and raw gyroscope/accelerometer sensor states, packaging them for the emulator.
2. **A Bluetooth Multi-Link Hub**: Seamlessly acts as a *Bluetooth Sender* (remotely forwarding controls) or as a *Bluetooth Receiver* (sub-hosting remote devices on local sub-player slots).
3. **A DSU Server + Audio Streamer**: Delivers control outputs directly to Dolphin via the Cemuhook DSU protocol while running a dedicated low-latency UDP socket listening for console-emulated speaker sounds.

```
          +--------------------------------------------------------+
          |                  Wii Controller App                    |
          |                                                        |
          |   +-------------+    +-------------+    +----------+   |
          |   |   Compose   |<-->|  ViewModel  |<-->| Room DB  |   |
          |   |   Screen    |    +-------------+    +----------+   |
          |   +-------------+           ^                          |
          |                             |                          |
          |      +----------------------+--------------------+     |
          |      v                                           v     |
          +------+------------------+             +----------+-----+
          |  DsuServer (Port 26760) |             |  AudioReceiver |
          |  Multi-Player (Slots)   |             | (Port 26761)   |
          +------+------------------+             +----------+-----+
                 ^            |                              ^
     DSU UDP     |            |                              | Live PCM
   Handshakes    |            v Input Reports                | UDP Bytes
                 |      +-----+---------------+              | (11025 Hz)
                 |      |                     |              |
                 +----->|  Dolphin Emulator   |<-------------+
                        | (PC/Mac/Android)    |   PC Loopback Sound Streamer
                        +---------------------+   (Python: sounddevice/numpy)
```

---

## 2. Cemuhook DSU (DualShock UDP) Server Protocol

The application implements the **Cemuhook DSU protocol (v1)** on port **`26760`** (UDP). It supports high-frequency asynchronous polling to deliver responsive control frames to the Dolphin emulator without lag.

### Socket and Event Loop
- **Listen Loop (`DsuServer.kt`)**: Initiates a `DatagramSocket`, binding to UDP local port `26760`. An active Kotlin Coroutine spins on `socket.receive()`. Incoming buffers are unpacked, validated via CRC32, and passed to a dedicated routing function `handlePacket()`.
- **Broadcast Outflow Loop**: An active broadcast thread loops at **`100Hz`** (10ms intervals). When clients are connected and subscribed, it formats an input report for every active controller slot and broadcasts it over UDP to the subscriber addresses. Client registrations expire if no message is received for 5 seconds.

### Standard Packet Frame Layout

Every DSU packet follows a rigorous payload definition. Total packet length is **20 bytes (Header)** + **Data Payload**. 

#### DSU Packet Header (20 Bytes)
| Byte Offset | Type | Description | Value / Mapping |
|---|---|---|---|
| `0x00 - 0x03` | `Char[4]` | Unique Protocol Magic String | `"DSUS"` (`0x44, 0x53, 0x55, 0x53`) |
| `0x04 - 0x05` | `UInt16` | Protocol Interface Version | `1001` (`0xE9, 0x03`, Little-Endian) |
| `0x06 - 0x07` | `UInt16` | Data Block Packet Length | Packet length minus first 16 bytes (Little-Endian) |
| `0x08 - `0x0B` | `UInt32` | Header Checksum CRC32 | Computed over the complete packet length with bytes 8-11 initialized to zero first. |
| `0x0C - 0x0F` | `UInt32` | Source Client Identifier | Non-zero arbitrary identifier code. |

---

### Message Transaction Protocol

The `handlePacket()` routing engine processes three primary request message IDs incoming from the Dolphin emulator:

#### 1. Version Info Request (`0x100000`)
- **Incoming**: Emptied payload.
- **Outgoing Code (`sendVersionResponse`)**: Responds with a 4-byte payload mapping the protocol capacity (`[0x00, 0x01, 0x00, 0x00]`).

#### 2. Ports Info Request (`0x100001`)
- **Incoming**: Emulated slots requesting properties.
- **Outgoing Code (`sendPortsInfoResponse`)**: Responds with a 16-byte payload defining device registration configurations. The app checks if a slot `0..3` is connected and returns its characteristics:

```
  +------+------------+------------+------------+---------------+----------+----------+
  | Slot | Conn State | Device Model| Interf Type|  MAC Address  | Batt Lvl | Padding  |
  | (1B) |   (1B)     |   (1B)     |   (1B)     |     (6B)      |   (1B)   |   (1B)   |
  +------+------------+------------+------------+---------------+----------+----------+
```
- **Values**:
  * **Conn State**: `0x00` (Disconnected), `0x02` (Connected).
  * **Device Model**: `0x02` (Full Gyro controller).
  * **Interf Type**: `0x02` (Wireless).
  * **MAC Address**: Unique derived controller hardware address (e.g., `00:1A:2B:3C:4D:5E` for Slot 1).
  * **Battery Level**: `0x05` (Full).

#### 3. Input Data / Subscribe Request (`0x100002`)
- Registers the UDP client target destination under memory caches `connectedClients` mapping against system time. If a client registers, it is stored and immediately receives input frames at 100 FPS (every 10ms).

#### Input Report Layout (Data payload size = 100 Bytes)
When serializing controls to Dolphin, the payload is structured as follows:

| Byte Offset | Target Variable | Unit Representation |
|---|---|---|
| `20` | Slot ID | `0` (Player 1) to `3` (Player 4) |
| `21` | Slot State Flag | `0` (Offline), `2` (Active Link) |
| `22` | Slot Gyro Capability | `2` (Full Gyro Dual-Shock / Wii Remote Extension) |
| `23` | Connection Type | `2` (Wireless Bluetooth/Wi-Fi Client) |
| `24 - 29` | Device MAC | 6-byte hexadecimal sequence unique for each player slot |
| `30` | Battery Level | `0x05` (Full charge standard) |
| `31` | Active State Flag | `1` (Actively operational) |
| `32 - 35` | Packet Index Counter | Unsigned 32-bit incremental integer |
| `36` | Button Block 1 | Bitmasked: Minus (`0x01`), Plus (`0x08`), Up (`0x10`), Right (`0x20`), Down (`0x40`), Left (`0x80`) |
| `37` | Button Block 2 | Bitmasked: Button 2 / Triangle (`0x10`), Button B / Circle (`0x20`), Button A / Cross (`0x40`), Button 1 / Square (`0x80`) |
| `38` | Home Button | `1` if pressed, else `0` |
| `39` | Touchpad Click | `0` (Not utilized) |
| `40` | Analog Stick X | Unsigned converted byte: `-128 to 127` biased to offset `0..255` |
| `41` | Analog Stick Y | Unsigned inverted converted byte: `-128 to 127` biased to offset `0..255` |
| `42 - 43` | Right Stick X/Y | Biased `128` (Centered) |
| `44 - 47` | Analog D-Pad | `255` if pressed else `0` (Analog sensitivity map) |
| `48 - 51` | Analog Keys (1, B, A, 2) | `255` if pressed else `0` |
| `52 - 55` | L1, R1, L2, R2 bumpers | `0` standard |
| `56 - 79` | Custom Sensor blocks | Emulated fields (zero standard) |
| `80 - 91` | Accel X, Y, Z | 3 x Floats (4 bytes each) mapped in standard G force representation |
| `92 - 103` | Gyro X, Y, Z | 3 x Floats (4 bytes each) mapped in angular speed deg/s representation |

---

### Multiplayer Slot Management Grid (P1 - P4)
The DSU Server supports concurrent multi-client play. The server slots array maintains states for **4 independent players**:
- **Player 1 (Slot 0)**: Mapped to the host phone hardware inputs if not acting purely as a receiver.
- **Player 2–4 (Slots 1–3)**: Dynamically bound to paired wireless Bluetooth controllers joined to the server.

---

## 3. Wireless Bluetooth Sync Protocol (Sender / Receiver)

For multiplayer gameplay, secondary devices can act as **Senders**, linking over Bluetooth to a primary **Receiver** device. The Receiver acts as a bridge, reading inputs from all Senders and feeding them into the DSU Server slots.

```
 +------------------------+                      +------------------------+
 |   Sender Device (P2)   |                      |    Receiver Hub (P1)   |
 |  Reads Local Gyro/Key  |                      | Runs DSU Server / UDP  |
 |  Compresses State      |                      | Unpacks Multi-Devices  |
 +-----------+------------+                      +-----------+------------+
             |                                               ^
             |    Low-Latency Bluetooth RFCOMM Stream        |
             +-----------------------------------------------+
                  Bytes: [0xAA, 0x55, BUTTONS(2B), STICK(2B), ACCEL/GYRO(24B)]
```

### RFCOMM Connection Topology
- **UUID Link Identifier**: `7c97f48e-d9ce-4279-bfbf-26d60eae7a1b`
- **Receiver (Server)**: Listens for incoming socket connections on a loop (`listenUsingRfcommWithServiceRecord`). It welcomes up to 3 Senders concurrently, dynamically allocating them to Slots P2 -> P4.
- **Sender (Client)**: Establishes a connection to the chosen target hardware MAC address over Bluetooth Socket RFCOMM layers, and initiates an immediate **100Hz** continuous byte pushing loop.

### Binary Serialization Format
To ensure minimal overhead on standard Bluetooth links, states are packed into extremely compact, high-density **32-byte frames**:

| Byte Offset | Data Class | Payload Translation | Length (Bytes) |
|---|---|---|---|
| `0` | Sync Header 1 | `0xAA` (Frames boundary detection validation) | 1 |
| `1` | Sync Header 2 | `0x55` (Frames alignment verification) | 1 |
| `2 - 3` | Buttons Bitmask | 16-bit integer holding state of all 12 key sensors | 2 |
| `4` | Stick X Axis | Signed byte value (`-128` to `127`) | 1 |
| `5` | Stick Y Axis | Signed byte value (`-128` to `127`) | 1 |
| `6 - 9` | Accel X Float | IEEE 754 float representation | 4 |
| `10 - 13` | Accel Y Float | IEEE 754 float representation | 4 |
| `14 - 17` | Accel Z Float | IEEE 754 float representation | 4 |
| `18 - 21` | Gyro X Float | IEEE 754 float representation | 4 |
| `22 - 25` | Gyro Y Float | IEEE 754 float representation | 4 |
| `26 - 29` | Gyro Z Float | IEEE 754 float representation | 4 |
| `30 - 31` | Stream Padding | Zeros to satisfy 32-byte alignment | 2 |

#### Outbound Frame Synchronization Code Example
```kotlin
private fun currentSenderFrame(): ByteArray {
    val buffer = ByteBuffer.allocate(32)
    buffer.put(0xAA.toByte())
    buffer.put(0x55.toByte())
    buffer.putShort(senderButtons.toShort())
    buffer.put(senderStickX)
    buffer.put(senderStickY)
    buffer.putFloat(senderAccelX)
    buffer.putFloat(senderAccelY)
    buffer.putFloat(senderAccelZ)
    buffer.putFloat(senderGyroX)
    buffer.putFloat(senderGyroY)
    buffer.putFloat(senderGyroZ)
    return buffer.array()
}
```

### Dynamic Slot Assignment Algorithm
When a Sender client pairs with the Receiver Hub over RFCOMM, the Hub evaluates current occupancies to assign the player slot:
1. Iterates from index `1 to 3` (P2, P3, and P4 slots).
2. Assigns the client to the first empty slot.
3. If all multiplayer slots are full, it looks for index `0` (replacing the primary local host controller if offline).
4. If the device disconnects or has an exception during capture, the slot is freed up, allowing robust hot-swaps.

---

## 4. Wii MotionPlus Precision Sensors Engine

The app includes an implementation imitating **Wii MotionPlus** gyroscope hardware extensions. This system calibrates, denoises, and filters incoming IMU inputs from the phone.

### Calibration Bias Equation
To prevent standing drift (where your cursor moves slowly across the screen even when the controller is placed flat), a calibration window takes samples from the gyroscope over a 1.5-second span (15 samples at 100ms intervals):

$$\text{Bias}_{i} = \frac{1}{N} \sum_{j=1}^{N} \text{Sample}_{ij} \quad \text{for } i \in \{X, Y, Z\}$$

For all active operations, the bias is subtracted from the raw live sensor values:

$$\text{Gyro}_{\text{Adjusted}} = (\text{Gyro}_{\text{Raw}} - \text{Bias}) \times \text{Sensitivity}$$

### Sensor Conversions and Scaling

#### 1. Accelerometer (m/s² to Gs)
Android delivers raw accelerometer values in standard SI units ($\text{m/s}^2$). The DSU/Cemuhook standard requires accelerations scaled in force Gs ($1\text{G} \approx 9.80665\,\text{m/s}^2$).
- **Transformation Formula**:

$$\text{Accel}_{\text{DSU}} = \frac{\text{Accel}_{\text{SI}}}{9.80665}$$

#### 2. Gyroscope (rad/s to ^/s)
Android measures angular velocity in radians per second ($\text{rad/s}$). Cemuhook clients demand degree values per second ($^\circ\text{/s}$).
- **Transformation Formula**:

$$\text{Gyro}_{\text{DSU}} = \text{Gyro}_{\text{SI}} \times \frac{180}{\pi} \approx \text{Gyro}_{\text{SI}} \times 57.29578$$

#### 3. Sensitivity Rates
The user UI features a three-state slider affecting the sensitivity multiplier:
- **`Slow`**: `0.5x` (For sniper precision aiming)
- **`Mid`**: `1.0x` (Default balanced standard)
- **`Fast`**: `2.0x` (For swift, arcade-style racing and sword play)

---

### Shake Simulation Controller
The Wii Remote physical shake triggers high-frequency sensor impulses that match signature signatures in Wii games (e.g., throwing a spin shell in Mario Kart or spinning in New Super Mario Bros. Wii).
- **Visual Vibration Pulse**: The system injects a sine wave calculation of $1.5\,\text{G}$ directly overlaid on coordinates of Accelerometer $X$ to simulate quick lateral motion.
- **Gyro Acceleration Overlay**: Rotational velocity values around Gyroscope $c$-axes are augmented by high-amplitude cosine frequencies fluctuating at $450^\circ\text{/s}$. This triggers reliable shake recognition inside games with zero physical finger stress.

---

## 5. In-Hand Real-Time UDP Speaker Streamer

Wii remotes have an integrated speaker chip that plays immersive context-specific alerts. The application achieves this via a high-performance **UDP audio receiver server loop** on port **`26761`**.

```
 +-----------------------------+                          +--------------------------+
 |    PC / Dolphin Emulator    |                          |   Android Receiver App   |
 | Capture Speakers (WASAPI)   |                          | Init AudioTrack Mono     |
 | Downsample to 11025Hz PCM   +------------------------->| Raw UDP Bytes Write      |
 |   (Python Streamer Host)    |       UDP Socket         |   Low-Latency Playback   |
 +-----------------------------+                          +--------------------------+
```

### PCM Specification and Codecs
To match original Nintendo specifications and keep streaming latency visual-perfect (under 40ms):
- **Sampling Frequency**: `11025 Hz` (Standard Mono)
- **Data Encoding Format**: `16-bit Signed PCM`
- **Streaming Class**: Raw uncompressed byte arrays sent inside small payload blocks (e.g., **512 bytes** / sub 46ms buffering).

### Android `AudioTrack` Playback Pipeline
Inside the app (`AudioReceiverServer.kt`), an `AudioTrack` instance is built specifically configured for real-time low-latency game speech:
- **Usage**: `AudioAttributes.USAGE_GAME` (Forces low hardware buffer latency, routing sound directly to the primary media channel).
- **Content Type**: `AudioAttributes.CONTENT_TYPE_SPEECH` / `AudioAttributes.FLAG_LOW_LATENCY`.
- **Playback Mode**: `AudioTrack.MODE_STREAM`.

When the background UDP listener thread receives payload bytes on Socket `26761`, it ignores standard network wrappers and pipes raw bytes directly to the playback buffer using `audioTrack.write()`.

---

### Desktop Python Host Sound Loopback Wrapper
To route speaker sounds from the emulator on a PC to the phone, users can use the Python script **`dolphin_wiimote_sound_streamer.py`** included in the root directory.

#### Under the Hood
1. Utilizes the raw loopback audio capture library `sounddevice` paired with processing arrays in `numpy`.
2. It lists all virtual output devices available on the host PC (WASAPI Loopback, Linux PulseAudio, MacOS BlackHole) and lets you auto-select the main loopback interface.
3. Automatically downsamples captured host system system audio to the standard **11025Hz Mono signed short PCM** format.
4. Streamer pipes raw byte buffers directly to the configured Android Phone IP Address over UDP at Port `26761` with small block packets of 512 samples.
5. Includes a **built-in visual simulation fallback** generating elegant, retro high-chime sinewaves to test and verify receiver connectivity without needing any external audio capture modules on the desktop first.

#### Running the Streamer
```bash
# Install required Python dependencies
pip install sounddevice numpy

# Run streaming directly mapping the IP displayed on your phone's screen
python dolphin_wiimote_sound_streamer.py --ip <YOUR_PHONE_IP_SHOWN_IN_APP>
```

---

## 6. Dolphin Android Emulator Integration

When running the emulator and the Wii Controller app on the **same Android device**, the application provides integration tools to make configuration seamless:

```
  +---------------------------------+  Intent Launcher Helper  +------------------------------------+
  |    Wii Controller Application   +------------------------->|     Dolphin Emulator Android       |
  | (Saves profile / Runs server)   |  (Launches Dolphin package) |  (Retrieves Controller Input)    |
  +---------------------------------+                          +------------------------------------+
```

- **Launch Package Targeting**: The app automatically polls device package managers, checking for the presence of the three major Dolphin Android builds:
  1. `org.dolphinemu.dolphinemu` (Official Play Store Stable release)
  2. `org.dolphinemu.dolphinemu.debug` (Official developer debug client branch)
  3. `org.dolphinemu.dolphinemu.canary` (Experimental performance trial builds)
- **Launcher Execution**: Pressing the launching trigger within the VM configures a global Android launch intent, attaches the `FLAG_ACTIVITY_NEW_TASK` stack flag, and launches Dolphin.
- **Alternate / Virtual Mappings**: Players can comfortably map bindings in Dolphin using the local DSU device loopback server at address `127.0.0.1:26760`. This enables low-latency IMU gyroscope aiming directly on the phone, bringing full console-style controller support to on-the-go emulator gameplay.

---

## 7. Database Persistence & Diagnostics Telemetry

The application leverages a robust **Room Database** framework (`WiiControllerDatabase`) to persist state histories and diagnostic metrics across gameplay sessions.

- **Historical DB Schema (`ConnectionHistoryEntity`)**:
  * `id`: Primary Auto-Generated Key
  * `timestamp`: Epoch millisecond marker of session start
  * `durationSeconds`: Total active gameplay session length
  * `packetsSent`: Total outgoing command reports delivered to Dolphin
  * `packetsReceived`: Total incoming request reports handled from Dolphin
  * `role`: Connection mode tracked (`HOST_SERVER`, `SENDER`, or `RECEIVER_HUB`)
- **Telemetry State Flows**:
  * Real-time network statistics (total packets sent, current package FPS frequency, packet packet index) are mirrored from the active threads to Kotlin `MutableStateFlow` structures inside the `WiiControllerViewModel`.
  * The front-end UI binds directly to these StateFlows using `collectAsStateWithLifecycle()`, displaying green telemetry indicators, high-speed line charts, and active slot counts. This helps confirm that your controller packets are flowing correctly with sub-millisecond precision.
