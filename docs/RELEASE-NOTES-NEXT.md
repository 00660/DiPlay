# DiPlay next release notes

Changes through DiPlay 0.2.19 are documented in [0.2.19 release notes](RELEASE-NOTES-0.2.19.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Connection setup and recovery

- Wired CarPlay comes back within seconds after **Save and reconnect**. DiPlay ended its session and started a new one at once, and the iPhone, still in CarPlay mode from the old session, accepted iAP2 and StartSession but opened no CarPlay connection until it reset USB 30–150 s later ([#335](https://github.com/shihabal3amri/DiPlay/issues/335)). When the iPhone is still in the mode of a session DiPlay started on the same USB connection, DiPlay now asks for CarPlay mode again first, as a fresh connection does. The report shows `previousSessionOnThisEnumeration=true action=request-transition`. Pending in-car acceptance.

## Display

- **CarPlay map on dashboard (experimental)** finds the cluster on BYD firmware that names it `fission_magicsentry` (controller 13) or `sission_magicsentry` (controller 17), at the same 1920×720 size as DiLink 4's `fission_bg_xdjaVirtualSurface`. A controller-13 car listed it as a presentation display, but DiPlay looked only for the DiLink 4 name, so nothing appeared on the cluster. A build that accepted the name drew the map there ([#552](https://github.com/shihabal3amri/DiPlay/issues/552)). Pending in-car acceptance on the release build.

- On DiLink 3, the settings card for the dashboard map no longer offers the two DiLink 4 cluster routes (**DiLink 4 cluster video via ADB** and **Platform 21 cluster task**). Either one stops DiLink 3 from creating its dashboard display, so the map stayed off, and only the ADB route showed a warning ([#722](https://github.com/shihabal3amri/DiPlay/issues/722)). When either is on, the card and the setup guide show the warning with a button that turns both off.
- **CarPlay map on dashboard (experimental)** uses the same full-map and side-map layers on the Leopard 5 Ultra (system 34.1.23) as on the measured DiLink 5.1 firmware. It drew into a layer this cluster never shows ([#301](https://github.com/shihabal3amri/DiPlay/issues/301)). Pending in-car acceptance.

## Diagnostics

- The phone page's server (**Get the report on a phone**, settings backup) wakes from waiting for a browser every 0.25 s instead of only after its 10-minute idle timeout, so closing it never waits on a pending accept. On the macOS JVM used for the unit tests, closing it while it waited hung the test run.

- On a head unit where BYD's HUD gateway package exists but refuses DiPlay's binding, DiPlay no longer retries it every 300 ms. On a Bliss OS tablet that was about 200 refused binds a minute, each logged by DiPlay and by Android ([#609](https://github.com/shihabal3amri/DiPlay/issues/609)). Retries now back off from 0.3 s to one minute, and the refusal is logged once.
