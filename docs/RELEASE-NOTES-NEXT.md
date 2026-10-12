# DiPlay next release notes

Changes through DiPlay 0.2.19 are documented in [0.2.19 release notes](RELEASE-NOTES-0.2.19.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Audio, calls and Siri

- An app voice message can use the head unit's microphone. DiPlay opened the microphone only after the iPhone's first audio packet, and an app that records a voice message (Douyin on a Leapmotor T03, [#566](https://github.com/shihabal3amri/DiPlay/issues/566)) asks for the microphone without playing anything, so recording never started. When no audio arrives within 0.5 s, DiPlay now opens the microphone anyway. Calls still wait for the call audio. The report logs `Audio: microphone started without downlink audio`. Pending in-car acceptance.
- When the head unit's microphone returns no audio for 1 s, DiPlay restarts recording up to three times, and no longer reads the empty microphone in a tight loop. On the same Leapmotor T03 (Android 7.1), some recordings started with no input device and returned nothing for the whole Siri request or call ([#566](https://github.com/shihabal3amri/DiPlay/issues/566)). The report logs `Microphone: recording restarted … reason=no-audio routedDeviceType=…`. Pending in-car acceptance.
