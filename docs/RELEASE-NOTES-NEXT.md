# DiPlay next release notes

Changes through DiPlay 0.2.19 are documented in [0.2.19 release notes](RELEASE-NOTES-0.2.19.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Diagnostics

- The phone page's server (**Get the report on a phone**, settings backup) wakes from waiting for a browser every 0.25 s instead of only after its 10-minute idle timeout, so closing it never waits on a pending accept. On the macOS JVM used for the unit tests, closing it while it waited hung the test run.

- On a head unit where BYD's HUD gateway package exists but refuses DiPlay's binding, DiPlay no longer retries it every 300 ms. On a Bliss OS tablet that was about 200 refused binds a minute, each logged by DiPlay and by Android ([#609](https://github.com/shihabal3amri/DiPlay/issues/609)). Retries now back off from 0.3 s to one minute, and the refusal is logged once.
