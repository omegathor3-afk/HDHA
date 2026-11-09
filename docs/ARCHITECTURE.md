# Architecture and engineering decisions

## Scope
HDHA inspects external HDDs and SSDs connected to Android through USB OTG. It discovers USB devices, classifies Mass Storage interfaces, and performs read-only SCSI INQUIRY and SAT ATA IDENTIFY DEVICE where supported.

## Layers
- UI: Jetpack Compose; clearly separates device discovery, identity and health reporting.
- USB discovery: Android USB Host API (UsbManager, UsbDevice, UsbInterface, UsbEndpoint).
- Descriptor classifier: identifies BOT, UAS, CBI/CBB and unknown protocol values; checks SCSI-transparent subclass and Bulk IN/OUT endpoints.
- BOT transport: claims the selected interface, sends a 31-byte CBW, receives the data-in phase and validates the 13-byte CSW, including signature, tag, status and residue.
- SCSI identity: INQUIRY (6-byte CDB, 36-byte allocation) returns peripheral type, removable flag, vendor, product and revision.
- SAT probe: ATA PASS-THROUGH(16), PIO data-in, IDENTIFY DEVICE (0xEC), requesting exactly one 512-byte identification block.
- ATA IDENTIFY parser: decodes serial, firmware, model, SMART supported/enabled bits, LBA28/LBA48 capacity and media rotation information. It validates the optional checksum when the 0xA5 signature is present.
- Future SMART support: SMART READ DATA, SMART READ THRESHOLDS and SMART RETURN STATUS. Preserve raw bytes and do not assume vendor-specific raw attribute values have universal meanings.

## SAT and BOT safeguards
- A valid ATA IDENTIFY response with BOT command status PASS confirms that this bridge passed the ATA PASS-THROUGH command.
- A failed or rejected command is indeterminate; it does not prove SAT is unsupported and never means the drive is healthy.
- Only SCSI INQUIRY and ATA IDENTIFY DEVICE are issued. Neither modifies media or reads user filesystem sectors.
- USB transfers have bounded timeouts. The interface is released only if successfully claimed, and the connection is closed on completion or failure.
- BOT reset recovery is not attempted automatically after a phase error; reconnect the drive before retrying.
- UAS and CBI/CBB are not implemented. Some bridges expose BOT but reject ATA pass-through.

## Compatibility boundary
USB descriptors do not prove that a bridge supports SAT or passes ATA commands. Record the USB-SATA bridge chipset, firmware, drive model and phone/adapter combination during physical validation.

## Safety rules
1. No filesystem writes, SMART enable/disable changes, destructive write tests, formatting, repair, or sector remapping.
2. Transport failure means Unavailable/Not confirmed, never Healthy.
3. SMART overall PASS does not guarantee future reliability.
4. Separate raw values, normalized values, source and interpretation.
5. Permission denial, disconnect and timeout must release resources safely.
6. Do not infer SMART health from SCSI INQUIRY or ATA IDENTIFY identity data.

## Milestones
1. USB discovery, permission and descriptor classification — implemented.
2. Read-only BOT transport and SCSI INQUIRY — implemented; physical validation pending.
3. SAT probe and read-only ATA IDENTIFY DEVICE — implemented; physical validation pending.
4. SMART data retrieval and interpretation — future work.
5. Local-only health history and export — future work.
