# HDHA — Hard Drive Health for Android

Android app for inspecting HDDs and SSDs connected through USB OTG.

## Current status
**Foundation stage — USB discovery only.** This build enumerates USB devices, identifies Mass Storage interfaces, displays descriptors/endpoints, and requests Android USB permission. It does NOT read SMART, issue ATA pass-through, scan sectors, or claim a drive is healthy.

## Safety
- Device discovery is not a health test.
- File access is not equivalent to raw block access or SMART access.
- SMART depends on the USB bridge, transport, drive firmware, and Android device.
- No write, erase, repair, format, or destructive test commands are included.
- Missing SMART access must be reported as Unsupported / unavailable, never Healthy.

## Build
Requires JDK 17 and Android SDK Platform 35. Run: gradle testDebugUnitTest assembleDebug

See docs/ARCHITECTURE.md and docs/DEVICE_VALIDATION.md.
