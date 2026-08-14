# RFC-005B - Local Network Discovery & Presence

**Project:** Warpnect
**Phase:** 5 - Discovery and Secure Session Management
**Status:** Complete
**Architecture Version:** 1.0 (frozen)
**SCL Protocol Version:** 1 (frozen)
**Native Bridge ABI Version:** 1 (frozen)
**Discovery Presence Schema Version:** 1

## Purpose

RFC-005B adds Android local discovery and a bounded, platform-neutral presence model. It can
advertise and browse Warpnect presence through LAN DNS-SD and Wi-Fi Direct DNS-SD. It does not
pair, authenticate, connect a Wi-Fi Direct peer, create a Session, establish a channel, or move
media or input traffic.

Discovery produces untrusted candidate routes only:

~~~text
DiscoveryPresenceId != DeviceId != SessionId != PathId
DNS-SD name / IP address / Wi-Fi Direct device address != peer identity
~~~

DeviceId is deliberately never placed in a DNS-SD record. A discovered service, alias, offered
role, availability value, presence ID, address, or Wi-Fi Direct locator is unauthenticated and can
be spoofed. RFC-005C and later RFCs own trust, identity verification, handshake, keys, and path
establishment.

## Discovery Presence Identity

DiscoveryPresenceId is a distinct non-zero 128-bit Kotlin type represented by two unsigned
64-bit values. It is generated with SecureRandom, is non-secret, is not persisted, and has no
trust or authentication meaning. It is intentionally type-distinct from DeviceId and SessionId.

Starting a full advertising epoch creates one fresh ID. The same ID is published through every
enabled backend for that epoch, allowing a browser to merge LAN and Direct observations without
broadcasting a stable device identity. Stopping advertising fully releases the ID and contact-port
lease; a later advertising start creates a new ID. A temporary availability suspension under
HideWhenUnavailable retains the current epoch so that re-advertising does not manufacture a new
logical presence. RFC-005B has no periodic presence-ID rotation timer.

The service instance name is Warpnect-<12-hex-presence-prefix>. It contains no Android device
name, account name, MAC address, hardware serial, DeviceId, or SessionId. Android NSD may rename
the requested instance to resolve a conflict; the effective name returned by NSD is retained in the
discovery snapshot for diagnostics.

## Discovery Schema V1

Both backends share one deliberately small DNS-SD metadata schema.

| Item | Value |
| --- | --- |
| Service type | _warpnect._udp (_warpnect._udp. for Android NSD) |
| Required TXT fields | dv, pid, role, av |
| Optional TXT fields | name, port |
| Schema version | dv=1 |
| Role encoding | h (Host), c (Client) |
| Availability encoding | 1 (available), 0 (unavailable) |
| Production TXT bound | 255 bytes |

pid is the complete 32-hex-character DiscoveryPresenceId. name is a bounded, sanitized,
presentation-only alias, with Warpnect Host as the privacy-neutral default. port is included for
Wi-Fi Direct DNS-SD because its service record does not carry the NSD registration port in the
same way. LAN uses the resolved NsdServiceInfo port instead.

The codec is pure Kotlin and strictly validates required fields, identifier encoding, role,
availability, port range, and the TXT size bound. Unknown keys are ignored for forward
compatibility. An invalid optional alias is discarded when the remaining record is valid. No JSON,
capability dump, device metadata, public key, certificate, pairing secret, token, MAC, codec list,
or media/input metadata is advertised.

## Architecture

~~~text
Android NSD / Wi-Fi P2P DNS-SD
              |
              v
platform discovery backends
              |
              v
LocalDiscoveryController + bounded DiscoveryPresenceCache
              |
              v
untrusted DiscoveredPresence summaries and route tokens
~~~

LocalDiscoveryController owns lifecycle, generation checks, advertisement construction,
availability updates, cache merging, expiry, counters, and snapshots. It does not own
SessionManager, although SessionManagerHostAvailabilityProvider can read RFC-005A capacity to
provide a current availability hint. It does not create or mutate sessions.

DiscoveryRouteToken is an opaque, controller-generation-scoped lookup token. It is not a PathId,
endpoint, peer identity, or persistent value. Android route objects remain private to the adapters.
A controlled cold-path lookup can expose a platform-neutral descriptor for a future RFC without
putting raw addresses or Android framework objects in the normal UI snapshot.

## LAN DNS-SD

AndroidLanNsdDiscoveryBackend uses NsdManager to:

- reserve a real ephemeral UDP contact port before registration;
- register the common advertisement with registerService;
- browse only the Warpnect service type with discoverServices;
- resolve a found service before publishing a usable LAN route; and
- remove its route immediately on onServiceLost.

The contact socket reserves a valid bootstrap port only. RFC-005B starts no receive worker, parses
no datagrams, and accepts no pairing or handshake protocol on that socket. A later RFC may reuse,
handoff, or replace this lease.

A resolved LAN route privately retains all usable addresses rather than treating the first IPv4
address as identity. On API levels/extensions that expose them, every NsdServiceInfo host-address
candidate and the associated Network are retained. Older compatibility paths retain the legacy
resolved host. Future RFC-005G performs endpoint selection.

NSD resolution is deduplicated by service key and is bounded to 64 pending resolutions. The
effective registered service name is recorded from onServiceRegistered; the requested name is never
assumed to have survived conflict resolution.

For older platform configurations, the LAN adapter acquires an application-managed
non-reference-counted Wi-Fi MulticastLock only while LAN discovery or advertising is active and
releases it on stop, failure, and close. Android T Extension 7 and newer foreground NSD
configurations use platform-managed multicast behavior, so no compatibility lock is acquired there.

## Wi-Fi Direct DNS-SD

AndroidWifiDirectDnsSdDiscoveryBackend initializes one WifiP2pManager Channel on the discovery
control looper and uses:

- WifiP2pDnsSdServiceInfo plus addLocalService for advertisement;
- WifiP2pDnsSdServiceRequest plus addServiceRequest for targeted browsing; and
- discoverServices with DNS-SD response listeners for observations.

The Direct adapter stores a WifiP2pDevice only as a bounded, private, opaque route locator. Its
device address is never a Warpnect identity and is not placed in a normal discovery snapshot. P2P
channel loss becomes an explicit Direct backend failure. There is no busy scan loop and no
unbounded automatic retry loop.

WifiP2pManager.connect() is **not implemented**. WifiP2pManager.createGroup() is **not
implemented**. RFC-005B only exposes a future Direct candidate path; RFC-005G owns actual path
establishment and selection.

## Presence Cache and Cross-Backend Merge

The cache is receiver-local and uses an injectable local monotonic clock. Its default and hard
maximum is 64 discovered presences, with at most two route kinds per presence: LAN and Direct.
The default stale timeout is 30 seconds and the control context checks expiry every 5 seconds.
When full, the cache rejects a new candidate deterministically and increments capacityDrops; it
does not evict an existing visible candidate randomly.

An observation with the local advertising presence ID is suppressed as self-discovery. Two routes
merge only when they have the same presence ID and consistent required metadata (schema version,
role, and availability). A conflicting observation is marked Conflicted rather than silently
merged. This merge is a convenience hint, not cryptographic proof that two advertised routes
belong to one device.

Routes have independent lifecycle. Losing a LAN route does not remove a presence while a Direct
route remains. A presence disappears only after every route is lost or stale. Raw Android route
locator maps are also bounded to 64 entries; no route data is persisted.

## Availability and Capacity

DiscoveryAvailability distinguishes Available, Unavailable, and local AtCapacity states. Only 1 or
0 is advertised, so discovery does not publish exact session counts. Availability is an untrusted
hint; later session establishment must validate capacity again.

The default DiscoveryVisibilityPolicy is HideWhenUnavailable. Under it, an unavailable Host
unregisters the advertisements while active sessions remain untouched. AdvertiseUnavailable is an
explicit alternative that keeps both backend advertisements active with av=0. One immutable logical
advertisement is built before it is submitted to enabled backends so LAN and Direct share the same
epoch metadata.

SessionManagerHostAvailabilityProvider is a read-only bridge to RFC-005A. With the default
single-client policy, one live Host session makes the Host unavailable. If maxConcurrentClients=2,
zero and one live Host sessions remain available, while two makes it unavailable. Discovery never
creates one advertisement per client slot.

## Permissions and Capability Planning

The current app configuration is minSdk=26, compileSdk=35, and targetSdk=35. Accordingly,
RFC-005B does not declare or request Android 17/API 37 ACCESS_LOCAL_NETWORK permission yet. The
permission planner has an explicit future branch: when both target and runtime SDK are 37 or
higher, LAN discovery reports a typed LanPermissionRequired or LanPermissionDenied result instead
of requesting UI permission from a backend.

Wi-Fi Direct planning is also explicit and contains no hidden fallback to LAN:

- API 33+ requires NEARBY_WIFI_DEVICES when the target supports that permission model.
- API 32 and lower use ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION; the manifest bounds
  both permissions to API 32.
- Disabled location services, disabled P2P state, missing manager, and absent Wi-Fi Direct feature
  are typed conditions.
- The manifest declares CHANGE_WIFI_MULTICAST_STATE and NEARBY_WIFI_DEVICES with
  neverForLocation; low-level discovery code never calls requestPermissions().

With DirectAndLan, one successfully started backend can run the controller in RunningDegraded; its
snapshot makes the failed backend and error visible. LanOnly and DirectOnly treat their selected
backend failure as fatal.

## Lifecycle and Threading

The platform controller owns one HandlerThread named WarpnectDiscovery. It serializes NSD
callbacks, Wi-Fi Direct callbacks, bounded cache mutation, stale-route expiry, availability
updates, and advertisement re-registration. Public operations are synchronous cold-path calls onto
that control context. There is no main-thread requirement for normal bookkeeping.

The controller supports prepare, full start/stop, and independent advertising and browsing
operations. It uses controller and advertisement generations to ignore callbacks from a stopped or
older lifecycle. close is idempotent and prevents later callbacks from mutating the cache.

There is no discovery media worker, input worker, native UDP receive loop, session coroutine, busy
polling loop, permanent multicast lock, background service, P2P group, or connection attempt.

## Security Boundary and Non-Goals

Discovery presence is explicitly unauthenticated. DiscoveryPresenceId is not authentication;
DeviceId is not broadcast; discovery does not establish trust and does not create a Session.
Metadata is spoofable until later RFCs verify identity and authenticate a session.

RFC-005B introduces no pairing, trust store, cryptography, certificate, handshake, encryption,
anti-replay, channel negotiation, automatic path selection, reconnect/failover, mixer, media
fan-out, Wi-Fi Direct connection, or virtual peripheral backend.

It introduces no JNI, native SCL change, PacketHeader change, PayloadType change, or change to
Video Payload V1, Audio Payload V1, Input Payload V1, ClockSync, NACK, FEC, or
VideoResyncRequest. Discovery Presence Schema Version 1 is DNS-SD control-plane metadata only; it
is not an SCL protocol or payload version.

## Tests and Runtime Status

JVM tests cover presence identity, strict codec parsing and TXT bounds, self-discovery, LAN/Direct
merge and conflicts, independent route loss, exact expiry with an injected clock, cache capacity,
availability policies, RFC-005A capacity calculation, degraded backend policy, stale callbacks,
effective NSD registration names, route-token lifetime, Android permission planning, and multicast
lock planning.

Android runtime checks remain device-dependent. During RFC-005B verification, adb reported no
connected device or emulator. NSD runtime registration, LAN peer discovery, Wi-Fi Direct local
service advertisement, Wi-Fi Direct peer discovery, dual-path hardware merge, and Android 17
local-network permission behavior are therefore NOT RUN rather than inferred.
