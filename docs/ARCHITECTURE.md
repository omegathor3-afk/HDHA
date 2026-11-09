# Architecture and engineering decisions

## Scope
HDHA diagnoses external HDDs and SSDs connected to Android through USB OTG. The first milestone intentionally stops at USB enumeration and permission handling.

## Layers
- UI: Jetpack Compose; clearly distinguishes discovery from actual health tests.
- USB discovery: Android USB Host API (UsbManager, UsbDevice, UsbInterface, UsbEndpoint).
- Future transport: isolated USB Mass Storage Bulk-Only Transport implementation; evaluate UASP separately.
- Future SCSI/ATA: SCSI INQUIRY and ATA PASS-THROUGH (12/16) with SAT capability detection.
- Future SMART parser: preserve raw bytes; parse IDENTIFY DEVICE, SMART READ DATA, SMART READ THRESHOLDS, SMART RETURN STATUS and logs. Do not assume vendor-specific raw attributes have universal meaning.
- Future history: local-only snapshots and export.

## Compatibility boundary
Android USB Host grants access to USB interfaces/endpoints after user permission. It does not guarantee ATA pass-through. A USB-SATA bridge can expose ordinary storage while rejecting SAT commands. Record bridge chipset and firmware compatibility per physical test.

## Safety rules
1. Foundation is read-only and performs no filesystem writes.
2. No SMART enable/disable changes in the initial implementation.
3. No destructive write tests, formatting, repair, or sector remapping.
4. Transport failure means Unsupported/Unavailable, not Healthy.
5. SMART overall PASS does not guarantee future reliability.
6. Separate raw values, normalized values, source and interpretation.
7. Disconnect, timeout and permission denial must release resources safely.

## Milestones
1. USB discovery and permission (current).
2. SCSI Mass Storage transport and INQUIRY.
3. SAT probing and read-only ATA SMART commands, with bridge compatibility matrix.
4. SMART interpretation and history.
5. Optional bounded read-only surface scan with cancellation and power warnings.
