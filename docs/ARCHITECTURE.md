# Architecture and engineering decisions

## Scope
HDHA diagnoses external HDDs and SSDs connected to Android through USB OTG. The current milestone discovers USB devices, requests permission, and classifies Mass Storage interface descriptors.

## Layers
- UI: Jetpack Compose; clearly distinguishes discovery from actual health tests.
- USB discovery: Android USB Host API (UsbManager, UsbDevice, UsbInterface, UsbEndpoint).
- Descriptor classifier: identifies Bulk-Only Transport (BOT), UAS, CBI/CBB and unknown protocol values; checks SCSI-transparent subclass and Bulk IN/OUT endpoints.
- Future transport: implement BOT first; evaluate UASP separately.
- Future SCSI/ATA: SCSI INQUIRY and ATA PASS-THROUGH (12/16) with SAT capability detection.
- Future SMART parser: preserve raw bytes; parse IDENTIFY DEVICE, SMART READ DATA, SMART READ THRESHOLDS, SMART RETURN STATUS and logs. Do not assume vendor-specific raw attributes have universal meaning.
- Future history: local-only snapshots and export.

## Critical compatibility boundary
USB descriptors only advertise an interface and transport protocol. They do not prove that a bridge accepts SCSI commands, supports SAT, or passes ATA SMART commands. A USB-SATA bridge can expose ordinary storage while rejecting SAT commands. Record bridge chipset and firmware compatibility per physical test.

## Safety rules
1. Current implementation performs descriptor inspection only.
2. No filesystem writes, SMART enable/disable changes, destructive write tests, formatting, repair, or sector remapping.
3. Transport failure means Unsupported/Unavailable, not Healthy.
4. SMART overall PASS does not guarantee future reliability.
5. Separate raw values, normalized values, source and interpretation.
6. Disconnect, timeout and permission denial must release resources safely.

## Milestones
1. USB discovery, permission and descriptor classification (current).
2. SCSI Mass Storage Bulk-Only Transport and INQUIRY.
3. SAT probing and read-only ATA SMART commands, with bridge compatibility matrix.
4. SMART interpretation and history.
5. Optional bounded read-only surface scan with cancellation and power warnings.
