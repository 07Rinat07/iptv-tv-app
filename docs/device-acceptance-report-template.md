# Device Acceptance Report Template

Use this template for every pre-release candidate and attach the filled report to PR/release notes.

## Metadata
- Date (UTC):
- Build version / commit:
- Tester:
- Device model:
- OS version / build:
- Input mode tested: `D-pad` / `mouse` / `mixed`
- Network profile: `stable` / `weak` / `lossy`

## Candidate Build
- Artifact:
- SHA-256:
- Signed: `yes/no`
- Signature verification command + result:

## Mandatory E2E Results
| Scenario | Status (`PASS/FAIL`) | Notes |
|---|---|---|
| `Сканировать -> Найти -> Выбрать -> Проверить -> Сохранить -> Воспроизвести` |  |  |
| Manual import by URL |  |  |
| Manual import by text |  |  |
| Manual import by local file |  |  |
| Playlist editor mass actions |  |  |
| Custom playlist creation |  |  |
| Favorites persistence after restart |  |  |
| Player switch Internal/VLC + fallback |  |  |
| Engine connect + torrent descriptor resolve/playback |  |  |
| Diagnostics visibility (network/engine/logs) |  |  |

## Performance and Stability
- Cold start (target <= 4s):
- Channel start on stable network (target <= 3s):
- Long run duration:
- ANR count:
- Crash count:
- Memory trend observations:

## 8h Soak
- Start time:
- End time:
- Channel switch cadence:
- Total channel switches:
- Engine-resolved channels included (`yes/no`):
- Result:

## Weak-Network Validation
Applied shaping:
- Bandwidth:
- Latency:
- Packet loss:

Per profile observations:
| Buffer profile | Startup | Rebuffering | Recovery |
|---|---|---|---|
| MINIMAL |  |  |  |
| STANDARD |  |  |  |
| HIGH |  |  |  |
| MANUAL |  |  |  |

## Defects
| ID | Severity | Repro steps | Status |
|---|---|---|---|
|  |  |  |  |

## Final Decision
- Release recommendation: `GO / NO-GO`
- Blocking issues:
- Follow-up actions:
