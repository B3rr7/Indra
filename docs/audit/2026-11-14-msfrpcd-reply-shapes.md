# Measured `msfrpcd` reply shapes (msf 6.5.9-dev, moto g60, 2026-11-14)

Why this file exists: `OWN_TECHNOLOGY_PLAN.md` §2 was written from the RPC **documentation**, and
two of its claims were wrong. Everything below was measured against a running daemon and then
checked against the framework's own Ruby source, so the DTOs are built on bytes rather than on
docs. Nothing here is inferred.

## How it was measured

`msfrpcd` was started exactly the way `MsfLaunch.rpcd()` starts it, from `su`:

```
chroot <files>/msf /bin/sh -c \
  'HOME=/root; PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; \
   export HOME PATH; exec /msfrpcd -p 55553 -P msf -U msf -f -S -a 127.0.0.1 -n'
```

then `adb forward tcp:55553 tcp:55553` and a `python3` probe that speaks the same wire protocol as
`MsfRpcClient`: `POST /api/`, `Content-Type: binary/message-pack`, body
`[method, token?, *args]`. Sources pulled from the device for cross-check:
`lib/msf/core/rpc/v10/rpc_module.rb` (917 lines) and `rpc_session.rb` (603 lines).

Every string on the wire is MessagePack `bin` (Ruby `ASCII-8BIT`), keys included.

## 1. `module.options(mtype, mname)` → the bare option map

```
{ "RHOSTS": { "type": "rhosts", "required": true,  "advanced": false,
              "evasion": false, "desc": "The target host(s), …" },
  "VERBOSE": { "type": "bool", "required": false, "advanced": true,
               "evasion": false, "desc": "Enable detailed status messages", "default": false },
  … }
```

- **No `{"options" => …}` wrapper** (contrary to the plan). The map *is* the reply.
- `default` is present only when the option has one; `enums` only when the option offers more than
  one choice (`rpc_module.rb:450`, `if o.enums.length > 1`).
- Keys are the datastore names (`RHOSTS`, `RPORT`, `TARGET`, …), so they are what the user sets.

## 2. `module.info(mtype, mname)` → metadata **and** the target list

Keys observed for `exploit/windows/smb/ms17_010_eternalblue`:

```
type, name, fullname, rank, disclosuredate, description, license, filepath, arch, platform,
authors, privileged, check, default_options, notes, references, actions, stance, options,
targets, default_target
```

- `targets` is a **map of index → name** (`{"0" => "Automatic Targeting", "1" => "Windows 7 / 2008 R2"}`),
  not a list.
- `default_target` is the index MSF would pick.
- `options` is the same structure `module.options` returns.

## 3. `module.targets` does not exist

```
HTTP 500  {"error": true, "error_class": "ArgumentError",
           "error_string": "Unknown API Call: \"rpc_targets\""}
```

`rpc_module.rb` has no `rpc_targets`; the plan's `moduleTargets` method cannot be written. Targets
therefore come from `module.info` — which is also the cheaper call, because it supplies targets,
default target, rank and date at once.

## 4. `module.target_compatible_payloads(mname, target)` → `{"payloads": [...]}`

Two arguments, the **module name without its type**, and the target index as an integer:

```
module.target_compatible_payloads("windows/smb/ms17_010_eternalblue", 1)
→ {"payloads": ["generic/custom", "generic/shell_bind_aws_ssm", …]}   # 77 entries
```

`module.compatible_payloads(mname)` takes one argument and answers the same shape for "any target".

## 5. `module.execute(mtype, mname, datastore)` → `{job_id, uuid}`

`datastore` is a map of **string** values. Four distinct outcomes:

| Situation | HTTP | Body |
|---|---|---|
| started | 200 | `{"job_id": 2, "uuid": "qkd87EZwlxfx42lKo25YEKbs"}` |
| refused to start (e.g. `TARGET: 99`) | 200 | `{"job_id": None, "uuid": None}` |
| option validation failed | **500** | `{"error": true, "error_class": "Msf::OptionValidateError", "error_string": "One or more options failed to validate: RHOSTS."}` |
| non-scalar / oversized option | **400** | plain error body |

- `TARGET` **is** the datastore key for target selection; `TARGET: 0` started a job, `TARGET: 99`
  answered two nulls.
- The "two nulls" answer is the one that must never be read as success, and the 500 carries the
  real sentence ("One or more options failed to validate: RHOSTS.") that must reach the user
  instead of a generic failure.

## 6. `job.list` / `job.stop`

```
job.list → {"1": "Auxiliary: scanner/portscan/tcp", "2": "Exploit: multi/http/tomcat_mgr_deploy"}
job.stop("1") → {"result": "success"}
```

String keys, values shaped `"<Type>: <module name>"` (`rpc_job.rb:8`).

## 7. `module.results(uuid)` / `module.ack(uuid)`

```
module.results(uuid) → {"status": "ready"}                    # first poll
                     → {"status": "completed", "result": null}
                     → {"status": "errored", "error": …}
module.ack(uuid)     → {"success": true}
```

Statuses seen: `ready`, `running`, `completed`, `errored` (`rpc_module.rb:544`). A completed
auxiliary scan carries `result: null` — the *status* is the verdict, not the payload.

## 8. `session.list` fields (`rpc_session.rb:29`)

```
sid → {type, tunnel_local, tunnel_peer, via_exploit, via_payload, desc, info, workspace,
       session_host, session_port, target_host, username, uuid, exploit_uuid, routes, arch}
```

plus `platform` for meterpreter sessions. `via_exploit` is the module that produced it.

## What this changed in the design

| Plan said | Measured |
|---|---|
| `moduleTargets(type, name)` | does not exist — targets come from `module.info` |
| `module.options` returns `{"options" => …}` | the map is bare |
| `moduleExecute` null ⇒ failure | **three** failures (two nulls, HTTP 500 with a sentence, HTTP 400) and one transport failure; only the sentence is actionable |
| `target_compatible_payloads(module)` | takes the module name *and* the target index |

## Cleanup

The daemon was started by hand for this measurement. Stop it when the work is done:

```
adb shell 'su -c "killall msfrpcd"'
adb forward --remove tcp:55553
```
