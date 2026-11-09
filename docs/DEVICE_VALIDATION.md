# Physical-device validation checklist

Do not mark a physical test as passed until it has actually been run and its observations recorded.

## Record setup
- Phone model and Android version
- OTG adapter model
- Powered hub / power supply if used
- Enclosure or SATA-USB bridge model and chipset if known
- Drive model, interface, capacity and power requirements

## USB discovery
- [ ] Connect a USB flash drive; record VID/PID and interface descriptors.
- [ ] Connect a 2.5-inch SATA drive in a USB enclosure.
- [ ] Repeat through a powered hub if direct OTG power is insufficient.
- [ ] Connect/disconnect repeatedly; check for stale device entries.
- [ ] Deny permission; verify no communication is attempted.
- [ ] Grant permission; refresh and verify permission state.
- [ ] Connect a non-storage USB device; ensure it is not labeled as a disk.

## Future SMART validation
- [ ] Verify SAT / ATA PASS-THROUGH with a known-good desktop reference.
- [ ] Compare model, capacity and SMART attributes against smartctl.
- [ ] Test a bridge that does not pass SMART; app must report Unsupported.
- [ ] Validate raw bytes and checksums before interpreting attributes.
- [ ] Never test by writing to user data.

For every run record date/time, exact hardware, app build/commit, expected behavior, observations, logs and pass/fail. This checklist is not evidence that testing occurred.
