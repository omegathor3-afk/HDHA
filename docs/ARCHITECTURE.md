# Architecture and engineering decisions

## Scope
HDHA diagnoses external HDDs and SSDs connected to Android through USB OTG. The current implementation discovers USB devices, requests permission, classifies Mass Storage interfaces, and can issue a read-only SCSI INQUIRY over USB Bulk-Only Transport (BOT).

## Layers
- UI: Jetpack Compose; clearly distinguishes discovery and device identification from actual health tests.
- USB discovery: Android USB Host API (UsbManager, UsbDevice, UsbInterface, UsbEndpoint).
- Descriptor classifier: identifies BOT, UAS, CBI/CBB and unknown protocol values; checks SCSI-transparent subclass and Bulk IN/OUT endpoints.
- BOT transport: claims a matching interface, sends a 31-byte Command Block Wrapper, receives the INQUIRY data phase and validates the 13-byte Command Status Wrapper.
- SCSI identity: INQUIRY (6-byte CDB, 36-byte allocation) extracts peripheral type, removable flag, vendor, product and revision.
- SAT probe: ATA PASS-THROUGH(16), PIO data-in, IDENTIFY DEVICE (0xEC), exactly one 512-byte read-only identification block. A validated response confirms the bridge passed this command; failure is indeterminate, not proof SAT is absent.
- IDENTIFY parser extracts ATA serial, firmware, model, SMART supported/enabled bits, LBA48 support and rotation-rate word. Optional ATA checksum is validated when its 0xA5 signature is present.
- Future SMART: SMART READ DATA, SMART READ THRESHOLDS, SMART RETURN STATUS and logs. Preserve raw bytes; do not assume vendor-specific raw attributes have universal meaning.
- Future history: local-only snapshots and export.

## SAT / ATA IDENTIFY safeguards and limitations
- SAT is probed only through the SCSI-transparent BOT interface using ATA PASS-THROUGH(12).
- The only ATA command sent in this milestone is IDENTIFY DEVICE (0xEC), with a 512-byte data-in transfer. No SMART feature subcommands, user-sector reads, or writes are sent.
- SAT is reported as supported only when the bridge returns a valid BOT success status and a structurally valid 512-byte ATA IDENTIFY block. A rejected command means SAT was not confirmed, not that the drive is unhealthy.
- ATA strings are byte-swapped per ATA word. Capacity is derived from LBA48 words only when word 83 advertises LBA48; otherwise LBA28 words are used.

## BOT safeguards and limitations
- SCSI INQUIRY and ATA IDENTIFY DEVICE are the only commands sent. ATA IDENTIFY reads the drive's 512-byte identification block and does not modify media. SMART attributes are not read in this milestone.
- The app requires USB permission and a SCSI-transparent BOT interface with Bulk IN and Bulk OUT endpoints.
- CBW/CSW signatures, exact wrapper lengths, command tag, status and data residue are validated. Transfers have bounded timeouts.
- On failure, timeout, or disconnect, the claimed interface is released and the connection is closed. BOT reset recovery is intentionally not attempted automatically; reconnect the device after a phase error.
- UAS and CBI/CBB are not implemented. A bridge may expose BOT yet reject SCSI commands.

## Critical compatibility boundary
USB descriptors only advertise an interface and transport protocol. They do not prove that a bridge accepts SCSI commands, supports SAT, or passes ATA SMART commands. A USB-SATA bridge can expose ordinary storage while rejecting SAT commands. Record bridge chipset and firmware compatibility per physical test.

## Safety rules
1. No filesystem writes, SMART enable/disable changes, destructive write tests, formatting, repair, or sector remapping.
2. Transport failure means Unsupported/Unavailable, not Healthy.
3. SMART overall PASS does not guarantee future reliability.
4. Separate raw values, normalized values, source and interpretation.
5. Disconnect, timeout and permission denial must release resources safely.
6. Do not infer SMART health from SCSI INQUIRY identity data.

## Milestones
1. USB discovery, permission and descriptor classification (complete).
2. Read-only BOT transport and SCSI INQUIRY (implemented; hardware validation pending).
3. SAT probing and read-only ATA IDENTIFY DEVICE (implemented; hardware validation pending). SMART data commands remain future work.
5. SMART interpretation and history.
6. Optional bounded read-only surface scan with cancellation and power warnings.
