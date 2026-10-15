# DiPlay next release notes

Changes through DiPlay 0.2.20 are documented in [0.2.20 release notes](RELEASE-NOTES-0.2.20.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Vehicle

- The custom steering-wheel Siri key can optionally require a double press; the option is off by default. A single press remains available to the car, while the second release opens Siri after a configurable delay. The double-press interval and Siri delay are adjustable. The wheel-key service continues detecting the pair if the car assistant takes the foreground; the CarPlay window remains a fallback on cars whose assistant does not take focus.
