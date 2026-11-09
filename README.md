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

## Honest SMART interpretation
- HDHA does **not** calculate a drive-health percentage or remaining-life estimate. Normalized SMART values and attribute semantics are model/vendor dependent; averaging them into a universal percentage is not technically defensible.
- The app reports the ATA SMART RETURN STATUS signature as: threshold exceeded, no threshold exceeded, or unknown. "No threshold exceeded" is not the same as "healthy".
- Where a SMART attribute and a non-zero threshold are both present, HDHA reports a normalized Current value at/below that drive-reported threshold. This is a vendor-reported warning, not a lifespan prediction.
- Raw six-byte values are preserved as hex. HDHA does not infer sector counts, hours, temperature, or SSD wear from undocumented encodings.
- A result is only as trustworthy as the USB-SATA bridge's SAT pass-through and the data returned by the drive. Physical validation against smartctl on known hardware remains required before claiming device compatibility.
- Always keep independent backups. SMART can miss sudden failures.
