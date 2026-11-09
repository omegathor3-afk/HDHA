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
- SMART support: SAT ATA PASS-THROUGH(16) SMART READ DATA (0xD0), SMART READ THRESHOLDS (0xD1), and SMART RETURN STATUS (0xDA) with CK_COND followed by descriptor-format REQUEST SENSE. The ATA Return Descriptor is checked for standard threshold signatures. Preserve six raw attribute bytes as hex; do not assume vendor-specific raw values have universal meanings.

## SAT and BOT safeguards
- A valid ATA IDENTIFY response with BOT command status PASS confirms that this bridge passed the ATA PASS-THROUGH command.
- A failed or rejected command is indeterminate; it does not prove SAT is unsupported and never means the drive is healthy.
- Only SCSI INQUIRY, ATA IDENTIFY DEVICE, SMART READ DATA, SMART READ THRESHOLDS, SMART RETURN STATUS and SCSI REQUEST SENSE are issued. No SMART enable/disable commands or media writes are issued.
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

## Profiles, history and reporting
- Attribute labels are held in an extensible registry with generic and vendor-family fallbacks. Model-specific profiles can be added without changing the SMART wire parser.
- Raw six-byte ATA attribute values remain hexadecimal unless a verified model-specific decoder is supplied. Current/worst normalized values and thresholds are kept separate.
- Successful snapshots are stored locally in app-private SharedPreferences as bounded JSON history (maximum 500 samples); there is no cloud transfer.
- Trend analysis compares normalized values only where the profile declares a direction. Unknown raw changes are informational; threshold crossings and SMART RETURN STATUS failures are elevated conservatively.
- Plain-text report export uses Android's share sheet. Reports include identity, profile, attributes, status, alerts and limitations.

## Milestones
1. USB discovery, permission and descriptor classification — implemented.
2. Read-only BOT transport and SCSI INQUIRY — implemented; physical validation pending.
3. SAT probe and read-only ATA IDENTIFY DEVICE — implemented; physical validation pending.
4. SMART data retrieval and conservative raw interpretation — implemented; physical validation pending.
5. Local-only SMART history, conservative comparisons and plain-text report export — implemented; physical validation pending.
