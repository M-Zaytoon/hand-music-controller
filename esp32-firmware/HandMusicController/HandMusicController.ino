/**
 * @file      HandMusicController.ino
 * @brief     Gesture-controlled Bluetooth music and LED system (ESP32 side)
 * @author    Mohammad Zaytoon
 *
 * @details   An Android app tracks the user's hand with MediaPipe and sends short
 *            text commands over Bluetooth Classic (SPP serial). This sketch plays the
 *            matching WAV file from an SD card through an I2S audio converter and
 *            amplifier module (Freenove v1.5) and shows an instrument-specific
 *            animation on a WS2812B LED ring.
 *
 * Command format (sent by the app, one command per line):   <CMD>:<VOL>\n
 *
 *   CMD   instrument   WAV file on the SD card
 *   P1    piano        /p1.wav
 *   P2    piano        /p2.wav
 *   P3    piano        /p3.wav
 *   D1    flute        /f1.wav      (flute uses the letter D in the protocol)
 *   D2    flute        /f2.wav
 *   D3    flute        /f3.wav
 *   G1    guitar       /g1.wav
 *   G2    guitar       /g2.wav
 *   G3    guitar       /g3.wav
 *   STOP  stop audio and turn the LEDs off
 *
 *   VOL   10-100, sets the audio volume and the LED brightness
 */

#include <BluetoothSerial.h>
#include <Adafruit_NeoPixel.h>
#include "SD_MMC.h"
#include "Audio.h"

// ==============================================================================
//   HARDWARE PIN DEFINITIONS
// ==============================================================================

// I2S audio converter & amplifier (Freenove Audio Converter & Amplifier v1.5)
#define I2S_BCLK      26
#define I2S_LRC       25
#define I2S_DOUT      22

// SD card (1-bit mode)
#define SD_MMC_CLK    14
#define SD_MMC_CMD    15
#define SD_MMC_D0     2

// WS2812B NeoPixel ring
#define LED_PIN       27
#define LED_COUNT     8

// ==============================================================================
//   GLOBAL OBJECTS & VARIABLES
// ==============================================================================

BluetoothSerial SerialBT;
Adafruit_NeoPixel ring(LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800);
Audio audio;

String btBuffer = "";
String lastCommand = "";
bool isNotePlaying = false;

// ==============================================================================
//   HELPER FUNCTIONS
// ==============================================================================

/**
 * @brief Maps incoming volume (10-100) to LED brightness (40-220)
 */
void setDynamicBrightness(int vol) {
  int brightness = map(constrain(vol, 10, 100), 10, 100, 40, 220);
  ring.setBrightness(brightness);
}

/**
 * @brief Stops all currently playing audio and turns off LEDs
 */
void stopAllSystems() {
  audio.stopSong();
  ring.clear();
  ring.show();
  lastCommand = "";
  isNotePlaying = false;
}

// ==============================================================================
//   LED ANIMATION HANDLERS
// ==============================================================================

void renderPianoEffect(int finger, int vol) {
  setDynamicBrightness(vol);
  ring.clear();

  // Cyan, Blue, Indigo
  uint32_t colors[3] = {
    ring.Color(70, 220, 255),
    ring.Color(40, 120, 255),
    ring.Color(120, 60, 255)
  };

  uint32_t bg = ring.Color(0, 8, 25);
  int pattern[3][8] = {
    {1, 1, 0, 0, 0, 0, 0, 0}, // Note 1
    {1, 1, 1, 0, 0, 1, 1, 1}, // Note 2
    {1, 1, 1, 1, 1, 1, 1, 1}  // Note 3
  };

  for (int i = 0; i < LED_COUNT; i++) {
    ring.setPixelColor(i, pattern[finger][i] ? colors[finger] : bg);
  }
  ring.show();
}

void renderFluteEffect(int finger, int vol) {
  setDynamicBrightness(vol);
  ring.clear();

  // Teal, Sky Blue, Violet
  uint32_t baseColors[3] = {
    ring.Color(0, 200, 180),
    ring.Color(70, 170, 255),
    ring.Color(180, 100, 255)
  };

  uint32_t bg = ring.Color(0, 20, 18);

  for (int i = 0; i < LED_COUNT; i++) {
    int dist = abs(i - (finger * 2 + 1));
    if (dist > 4) dist = 8 - dist;

    int glow = map(dist, 0, 4, 255, 40);
    uint32_t c = baseColors[finger];

    uint8_t r = ((c >> 16) & 0xFF) * glow / 255;
    uint8_t g = ((c >> 8) & 0xFF)  * glow / 255;
    uint8_t b = (c & 0xFF)         * glow / 255;

    ring.setPixelColor(i, (glow < 80) ? bg : ring.Color(r, g, b));
  }
  ring.show();
}

void renderGuitarEffect(int finger, int vol) {
  setDynamicBrightness(vol);
  ring.clear();

  // Purple, Hot Pink, Magenta
  uint32_t bright[3] = {
    ring.Color(180, 0, 180),
    ring.Color(255, 0, 140),
    ring.Color(255, 80, 220)
  };

  uint32_t dim[3] = {
    ring.Color(18, 0, 18),
    ring.Color(20, 0, 14),
    ring.Color(22, 6, 22)
  };

  for (int i = 0; i < LED_COUNT; i++) {
    ring.setPixelColor(i, ((i + finger) % 2 == 0) ? bright[finger] : dim[finger]);
  }
  ring.show();
}

// ==============================================================================
//   COMMAND PROCESSOR
// ==============================================================================

/**
 * @brief Parses the Bluetooth command and triggers the corresponding audio/LED
 * @param data The incoming string from BT (format -> CMD:VOL)
 */
void processGestureCommand(String data) {
  int sep = data.indexOf(':');
  if (sep == -1) return;

  String cmd = data.substring(0, sep);
  cmd.trim();
  int vol = constrain(data.substring(sep + 1).toInt(), 10, 100);

  Serial.printf("[SYSTEM] Command Received -> %-4s | Volume: %d\n", cmd.c_str(), vol);

  if (cmd == "STOP") {
    stopAllSystems();
    return;
  }

  // Prevent re-triggering the same note while it's currently playing
  if (cmd == lastCommand) return;
  lastCommand = cmd;

  // Map app volume (10-100) to audio library volume (5-12; the library supports 0-21)
  audio.setVolume(map(vol, 10, 100, 5, 12));
  isNotePlaying = true;

  // --- PIANO ---
  if      (cmd == "P1") { audio.connecttoFS(SD_MMC, "/p1.wav"); renderPianoEffect(0, vol); }
  else if (cmd == "P2") { audio.connecttoFS(SD_MMC, "/p2.wav"); renderPianoEffect(1, vol); }
  else if (cmd == "P3") { audio.connecttoFS(SD_MMC, "/p3.wav"); renderPianoEffect(2, vol); }

  // --- FLUTE (protocol letter D) ---
  else if (cmd == "D1") { audio.connecttoFS(SD_MMC, "/f1.wav"); renderFluteEffect(0, vol); }
  else if (cmd == "D2") { audio.connecttoFS(SD_MMC, "/f2.wav"); renderFluteEffect(1, vol); }
  else if (cmd == "D3") { audio.connecttoFS(SD_MMC, "/f3.wav"); renderFluteEffect(2, vol); }

  // --- GUITAR ---
  else if (cmd == "G1") { audio.connecttoFS(SD_MMC, "/g1.wav"); renderGuitarEffect(0, vol); }
  else if (cmd == "G2") { audio.connecttoFS(SD_MMC, "/g2.wav"); renderGuitarEffect(1, vol); }
  else if (cmd == "G3") { audio.connecttoFS(SD_MMC, "/g3.wav"); renderGuitarEffect(2, vol); }
}

// ==============================================================================
//   INITIALIZATION
// ==============================================================================

void setup() {
  Serial.begin(115200);
  Serial.println("\n[SYSTEM] Booting Hand Music Controller...");

  // 1. Initialize SD card
  SD_MMC.setPins(SD_MMC_CLK, SD_MMC_CMD, SD_MMC_D0);
  if (!SD_MMC.begin("/sdcard", true, true, SDMMC_FREQ_DEFAULT, 5)) {
    Serial.println("[ERROR] SD Card Mount Failed!");
  } else {
    Serial.println("[OK] SD Card Ready");
  }

  // 2. Initialize I2S audio
  audio.setPinout(I2S_BCLK, I2S_LRC, I2S_DOUT);
  audio.setVolume(8);
  Serial.println("[OK] I2S Audio Ready");

  // 3. Initialize LEDs
  ring.begin();
  ring.setBrightness(40);
  ring.clear();
  ring.show();

  // 4. Boot animation
  for (int i = 0; i < LED_COUNT; i++) {
    ring.setPixelColor(i, ring.Color((i * 35) % 255, (i * 70) % 255, (i * 95) % 255));
    ring.show();
    delay(50);
  }
  delay(200);
  ring.clear();
  ring.show();

  // 5. Initialize Bluetooth Classic (the Android app connects by this board's MAC address)
  SerialBT.begin("Mohammed_Pro_Music");
  Serial.println("[OK] Bluetooth Ready. Waiting for connection...");
}

// ==============================================================================
//   MAIN LOOP
// ==============================================================================

void loop() {
  // Feed the I2S audio buffer (must run constantly for smooth audio)
  audio.loop();

  // Process incoming Bluetooth serial commands (one command per line)
  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\n') {
      processGestureCommand(btBuffer);
      btBuffer = "";
    } else {
      btBuffer += c;
    }
  }

  // Automatically turn off LEDs when the audio track finishes
  if (isNotePlaying && !audio.isRunning()) {
    ring.clear();
    ring.show();
    isNotePlaying = false;
    lastCommand = "";
  }
}
