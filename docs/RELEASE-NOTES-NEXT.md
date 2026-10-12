# DiPlay next release notes

Changes through DiPlay 0.2.19 are documented in [0.2.19 release notes](RELEASE-NOTES-0.2.19.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Diagnostics

- When DiPlay crashes, the next diagnostic report shows where: a new **Last crash recorded by DiPlay** section lists the exception, its causes and the code it was thrown from, redacted like the rest of the report. Android 11+ only recorded `reason=java_crash`, so a crash when the DiYou launcher card opens DiPlay could not be traced ([#463](https://github.com/shihabal3amri/DiPlay/issues/463)). The record stays on the head unit until a report is saved and shared.
