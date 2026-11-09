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

## Build
Requires JDK 17 and Android SDK Platform 35. Run: gradle testDebugUnitTest assembleDebug

See docs/ARCHITECTURE.md and docs/DEVICE_VALIDATION.md.
