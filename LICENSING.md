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

## Bundled SysML v2 Pilot Implementation

[Pilot 2026-09.1](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/releases/tag/2026-09.1)
uses the [Eclipse Public License 2.0](LICENSE-EPL). The Dockerfile builds the
revision in [`.pilot-version`](.pilot-version) with the
[missing reference ID patch](patches/pilot-missing-reference-id.patch) applied.
The patch is distributed under EPL-2.0. The image contains the resulting JARs
at `/opt/pilot/` and the corresponding upstream libraries at `/opt/sysml.library/`.

The existing `app/org/omg/sysml/plantuml/InheritKey.java` override retains the
[LGPL-3.0-or-later](LICENSE-LGPL) terms of the earlier Pilot source it was based on.
The [GPL-3.0 text](LICENSE-GPL) accompanies the LGPL notice.
