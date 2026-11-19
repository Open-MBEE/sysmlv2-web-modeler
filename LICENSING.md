# Licensing

This repository combines code under two different licenses. The split is
deliberate, and each component carries its own license file.

## Wrapper code: Apache License 2.0

Everything we (Planetary Utilities) wrote is licensed under the
[Apache License, Version 2.0](LICENSE):

- `app/SysMLVizServer.java`, `app/TextualModelService.java`,
  `app/ValidationProbe.java`
- `app/sysml_replace_commit.py`, `app/sysml_create_project.py`
- `app/static/*` (HTML, CSS, JS, images we created)
- `Dockerfile`, all files under `.github/`, `scripts/`, `tests/`
- `README.md`, `CHANGELOG.md`, governance docs

Source files carry the SPDX header:

```
SPDX-License-Identifier: Apache-2.0
Copyright 2026 Planetary Utilities, Inc.
```

## Bundled SysML v2 Pilot Implementation: LGPL-3.0-or-later

The SysML v2 Pilot Implementation
(<https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation>)
is licensed under [LGPL-3.0-or-later](LICENSE-LGPL) with a dual
GPL-3.0-or-later option. We use it under the **LGPL** branch.

The Dockerfile clones the upstream revision pinned in
[`.pilot-version`](.pilot-version), applies
[the missing reference ID patch](patches/pilot-missing-reference-id.patch),
and builds the jars that ship in the Docker image at `/opt/pilot/`.

### LGPL-3.0 compliance

LGPL-3.0 §4 requires that users be able to replace the bundled library
with a modified version. We satisfy this in two ways:

1. **Replaceable at runtime.** The Pilot jars sit on the classpath as
   separate files in `/opt/pilot/`. To use a modified
   version, mount or copy your own jars over `/opt/pilot/` and
   restart the container:

   ```
   docker run -v /path/to/your/pilot-jars:/opt/pilot -p 8088:8088 sysmlv2viz
   ```

2. **Replaceable at build time.** The Dockerfile accepts
   `--build-arg PILOT_REPO=<git-url>` so anyone can rebuild the
   image against a fork or different commit of the Pilot.

The corresponding source is the upstream revision in `.pilot-version` with the
patch above applied. The patch is distributed under LGPL-3.0-or-later.

### Bundled SysML/KerML standard libraries

The `*.kerml` and `*.sysml` files copied into `/opt/sysml.library/` in
the runtime image come from the upstream Pilot's `sysml.library/`
directory and are also LGPL-3.0-or-later. They are not modified.

## Why this split

The Apache 2.0 wrapper grants downstream users a permissive,
patent-grant-bearing license for everything we wrote. The LGPL
boundary applies only to the Pilot library we link against, which is
exactly what LGPL-3.0 was designed to allow: a non-LGPL application
linking with an LGPL library, without contaminating the application's
license.

If you fork only the wrapper code (no Pilot jars), you receive
Apache 2.0 only. If you use the Docker image, you receive Apache 2.0
for the wrapper plus LGPL-3.0 for the bundled Pilot. The combined
work is governed by both licenses for their respective components.

## Trademarks

- "Planetary Utilities" and "Starforge" are trademarks of Planetary
  Utilities, Inc. The Apache 2.0 license does not grant trademark
  rights (Apache 2.0 §6).
- "OpenMBEE" is a trademark of the OpenMBEE community.
- "SysML" and "KerML" are trademarks of the Object Management Group.

Use of these names in this repository is descriptive (identifying
provenance) and does not imply endorsement.

## Reporting a license concern

If you believe any file in this repository is misattributed or carries
a license incompatible with the declarations above, please open an
issue or email security@planetaryutilities.com.
