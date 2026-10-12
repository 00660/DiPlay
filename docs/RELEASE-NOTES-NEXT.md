# DiPlay next release notes

Changes through DiPlay 0.2.19 are documented in [0.2.19 release notes](RELEASE-NOTES-0.2.19.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Audio, calls and Siri

- **Settings → Audio → Calls on the loudspeaker** is for an Android phone or tablet used as the head unit. It plays CarPlay calls on the device's loudspeaker and uses its hands-free microphone. Android plays calls on such a device's earpiece and records them with the handset microphone, so the driver heard calls only close to the screen and had to speak into it (Xiaomi MIX 2S, Android 10, [#701](https://github.com/shihabal3amri/DiPlay/issues/701)). It is off by default and applies from the next call. The report adds `loudspeaker=true|false` to `Audio: mode entered communication`. Pending acceptance.
