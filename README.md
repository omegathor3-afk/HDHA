# HDHA — Hard Drive Health for Android

Android app for inspecting HDDs and SSDs connected through USB OTG.

## Current status
**USB discovery + read-only SMART retrieval stage.** This build enumerates USB devices, identifies BOT Mass Storage interfaces, runs SCSI INQUIRY, probes SAT with ATA IDENTIFY DEVICE, and (when SMART is already supported and enabled) reads SMART READ DATA, SMART READ THRESHOLDS and SMART RETURN STATUS through SAT ATA PASS-THROUGH(16).

## Safety
- Device discovery, SCSI INQUIRY, and ATA IDENTIFY are not health tests.
- File access is not equivalent to raw block access or SMART access.
- SMART depends on the USB bridge, transport, drive firmware, and Android device.
- Only SCSI INQUIRY, ATA IDENTIFY DEVICE, SMART READ DATA, SMART READ THRESHOLDS, SMART RETURN STATUS and REQUEST SENSE are sent. No SMART enable/disable, write, erase, repair, format or destructive test commands are included.
- SMART support/enabled bits come from ATA IDENTIFY. Each attribute's six-byte raw value is preserved as hex and is not treated as a universal integer.
- SAT failure is indeterminate and does not prove the drive is unhealthy or that every bridge lacks SAT.
- Missing SMART access must be reported as Unsupported / unavailable, never Healthy.

## SMART profiles, history and reports
- Attribute labels are selected from an extensible vendor/model profile registry, with a conservative generic fallback for unknown devices.
- Six-byte vendor raw values remain hexadecimal; the app does not guess units or convert them to sector counts, hours or temperatures without a verified model-specific decoder.
- Successful SMART reads are stored locally in app-private preferences (up to 500 samples). No account, cloud sync or network upload is used.
- Comparisons report only profile-backed normalized-value direction changes and threshold crossings. Unknown raw-value changes are informational, not health verdicts.
- Export creates a plain-text report through Android's share sheet, including device identity, attributes, history context, alerts and interpretation limits.

## Build
Requires JDK 17 and Android SDK Platform 35. Run: gradle testDebugUnitTest assembleDebug

See docs/ARCHITECTURE.md and docs/DEVICE_VALIDATION.md.

## HDHA estimated health percentage
- The dashboard shows an **HDHA estimate** only when SMART RETURN STATUS is explicitly clear and at least two supported normalized health attributes (IDs 5, 10, 187, 188, 196, 197, 198) have valid threshold entries.
- The estimate is a weighted summary of normalized current values, with greater weights for reallocated, pending and uncorrectable-sector indicators. It is not a manufacturer rating, remaining-lifespan percentage, or probability of failure.
- If SMART status is unknown, a drive-level threshold is exceeded, a supported attribute reaches its threshold, or evidence is insufficient, no percentage is shown; the app displays the reason instead.
- Confidence is labelled Limited for two or three supported indicators and Moderate for four or more. The app does not interpret vendor-specific raw bytes to manufacture a score.
- Historical scans show the change in estimate only when both samples contain a valid percentage for the same device identity. A clean score never guarantees future reliability; maintain independent backups.
