# HDHA — Hard Drive Health for Android

Android app for inspecting HDDs and SSDs connected through USB OTG.

## Current status
**USB discovery + read-only identification stage.** This build enumerates USB devices, identifies BOT Mass Storage interfaces, requests Android USB permission, runs SCSI INQUIRY, and probes SAT with ATA PASS-THROUGH(16) / IDENTIFY DEVICE (0xEC). It reads only the 512-byte ATA identity block; it does not read SMART attributes or scan user sectors.

## Safety
- Device discovery, SCSI INQUIRY, and ATA IDENTIFY are not health tests.
- File access is not equivalent to raw block access or SMART access.
- SMART depends on the USB bridge, transport, drive firmware, and Android device.
- Only SCSI INQUIRY and ATA IDENTIFY DEVICE are sent. No write, erase, repair, format, SMART feature, or destructive test commands are included.
- SMART supported/enabled bits shown in the UI come from ATA IDENTIFY metadata; SMART attribute values are not read yet.
- SAT failure is indeterminate and does not prove the drive is unhealthy or that every bridge lacks SAT.
- Missing SMART access must be reported as Unsupported / unavailable, never Healthy.

## Build
Requires JDK 17 and Android SDK Platform 35. Run: gradle testDebugUnitTest assembleDebug

See docs/ARCHITECTURE.md and docs/DEVICE_VALIDATION.md.
