# Native UPnP device bridge

Uses the Portable SDK for UPnP Devices (pupnp), revision
`79fc9f5dbd208888b220dcc0f4d0464bebbfa756`, from its public repository.
Set `-PpupnpSourceDir` to its checkout, or place it beside this repository.
Its original three-clause BSD licence is included in the app notice. Credits
come from its own README and COPYING: Intel Corporation, Rémi Turboult,
Michel Pfeiffer and the pupnp contributors. No contact addresses are copied.

The bridge delegates SSDP, HTTP, SOAP parsing and event subscriptions to pupnp.
Application code supplies the device/service descriptions and service state.
A native bridge alone does not establish MediaRenderer conformance.

This optional component requires Android API 24: pupnp uses getifaddrs, which
Bionic introduced at that level. The integrated movie receiver requires API 24 because its Media3 dependency
also requires that level. The original upstream RDP receiver supports API 23.
Only one UPnP device owns the process-wide SDK at a time. Start and close on a
worker thread; action callbacks must not close the SDK synchronously.
