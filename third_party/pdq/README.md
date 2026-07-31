# Vendored PDQ Java reference implementation

The source files under
`services/media/src/main/java/pdqhashing/` are copied without modification
from Meta's open-source ThreatExchange PDQ Java reference implementation:

- Repository: https://github.com/facebook/ThreatExchange
- Commit: `baefb4ed67b6cdc1d4c82dbaef858d50866ac424`
- Upstream path: `pdq/java/src/main/java/pdqhashing/`
- License: BSD; see `LICENSE` in this directory.

Only the hashing types and utilities needed to compute a PDQ hash are vendored.
This keeps container builds reproducible and avoids a runtime service or
network dependency.
