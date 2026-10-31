# GID (13.1.2)

GIDs replace phone numbers in this fork: `+030 12345678`. The first three digits
are a UN M49 geographic region; the final eight digits are random, preserve leading
zeroes and cannot be `00000000`. The complete GID is unique. Account UUIDs and TL
numeric IDs remain unchanged when a GID changes.

Registration requests approximate location permission only when Register is pressed.
The app reverse-geocodes one current/recent location to an ISO country code and sends
only that country code to the backend. It stops location updates after a fix, timeout
or cancellation. No coordinates are sent or stored. Permission denial, disabled
location, a fix older than ten minutes, geocoder failure or a 12-second timeout uses
World (`001`). No SIM-country or IP-based guess replaces an unavailable location.
The region reflects device geocoding, not a verified identity or residence.

The server maps the country to the most specific available M49 region (China →
Eastern Asia `030`, US → Northern America `021`, Brazil → South America `005`).
It allocates the suffix with a cryptographic RNG inside the atomic Store write lock
and retries collisions before saving the account and its session.

Existing users without a GID receive a persistent `+001 …` on first startup of the
new backend. A `user.gid_changed` event refreshes cached identities in active clients.
GID changes are delivered through the original `TL_updateUserPhone` class. The TL
`phone` field carries the eleven GID digits as an internal compatibility field; REST
user objects expose a formatted `gid`. No phone verification or SMS is used.

Settings → Account → Personal Info shows the GID. The existing Telegram change-number
intro is reused with GID text; the editor and region picker use Telegram's standard
list, input and dialog components. The picker includes all 29 M49 geographic regions
other than World. It uses letter abbreviations in place of flags. These letters are
app display abbreviations; M49 itself standardizes the numeric codes. A user with a
World GID must choose another region to submit a change. The server rejects unknown
regions, country codes, World changes, non-eight-digit suffixes, all-zero suffixes,
and collisions (409 `gid_taken`). Checking and saving occur in one transaction.

API additions:

- Registration optional field `locationCountry` (ISO alpha-2); omission falls back to `001`.
- Authenticated `GET /api/gid/regions`: 29 selectable regions with English/Chinese names and abbreviations.
- Authenticated `PUT /api/me/gid`: `{ "regionCode": "030", "number": "12345678" }`; returns the updated TL user.

The client bundles `Shared/botgram-m49.json` with `Shared/botgram-m49.manifest.json`.
The backend owns an independent pinned copy under `Resources/M49/` in its own repo.
Both carry matching snapshot versions and data SHA-256 values; neither build reads
the other repository or downloads geographic data. The backend maintenance scripts
retrieve the official UN M49 table only when deliberately updating the snapshot;
see its `Resources/M49/README.md` for fixed policy, explicit compatibility overrides,
provenance and offline verification. `Tools/Verify-BotgramM49.ps1` checks the client copy.
Numeric definitions: https://unstats.un.org/unsd/methodology/m49/overview/
The original mapping was imported from
https://github.com/lukes/ISO-3166-Countries-with-Regional-Codes/blob/master/all/all.csv
on 2026-11-01; source cached at `D:\BotgramBuild\downloads\m49-countries.csv`.
CSV SHA-256: `347BBA35029F804F53780062052499781D267B8A5D887BF3B051E80A68390D6D`.
Chinese names and display abbreviations are maintained in the shared data.

Verification: `node ../Botgram.Backend/tests/gid.mjs`, existing smoke/schema tests, and
`Tools/Verify-BotgramTL.ps1 -FixturePath ../Botgram.Backend/obj/gid-tl-fixtures.json`.
Device permission dialogs, reverse geocoding and the editor still require real-device
validation; host tests exercise backend transactions and original TL serialization.
