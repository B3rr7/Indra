#!/usr/bin/env bash
#
# Fails when R8 stripped or renamed the classes the native client resolves by name.
#
# indra/jni/cSploitClient/cache.c looks these up with FindClass("<exact name>") and
# GetStaticMethodID("onEvent", ...). If R8 renames any of them the release APK still
# builds and installs, then silently loses every daemon event: no command output, no
# ChildEnd, so every tool runs until its timeout. A debug build does not minify, which
# is why this regression is invisible until a release is on a device. Guard the
# artefact, not the source.
#
# Usage: scripts/verify-release-keeps.sh [path/to/mapping.txt]

set -euo pipefail

MAPPING="${1:-indra/build/outputs/mapping/release/mapping.txt}"
STATUS=0

if [ ! -f "${MAPPING}" ]; then
  echo "FAIL: no R8 mapping at ${MAPPING} (did the release build run with minify enabled?)" >&2
  exit 1
fi

# A kept class appears as "name -> name:"; a renamed one appears as "name -> a.b:".
require_kept() {
  local name="$1"
  if grep -qE "^${name} -> ${name}:" "${MAPPING}"; then
    echo "ok   ${name//\\/}"
  else
    echo "FAIL ${name//\\/} was renamed or stripped" >&2
    STATUS=1
  fi
}

# Resolved by FindClass from cSploitClient/cache.c.
require_kept 'org\.indra\.android\.core\.child\.ChildProcessManager'
require_kept 'org\.indra\.android\.events\.ChildEnd'
require_kept 'org\.indra\.android\.events\.ChildDied'
require_kept 'org\.indra\.android\.events\.Newline'
require_kept 'org\.indra\.android\.events\.StderrNewline'
require_kept 'org\.indra\.android\.events\.Port'
require_kept 'org\.indra\.android\.events\.Os'
require_kept 'org\.indra\.android\.events\.Message'

# Resolved by GetStaticMethodID("onEvent"); a renamed or reshaped callback is just as
# fatal as a renamed class.
if grep -qE 'void onEvent\(int,org\.indra\.android\.events\.Event\):.* -> onEvent$' "${MAPPING}"; then
  echo "ok   ChildProcessManager.onEvent(int, org.indra.android.events.Event)"
else
  echo "FAIL ChildProcessManager.onEvent(int, Event) was renamed or reshaped" >&2
  STATUS=1
fi

# Control: if nothing in this package was renamed, minification is off and the checks
# above prove nothing (they would pass on any build).
if grep -qE '^org\.indra\.android\.core\.child\.ChildProcess -> [^ ]+:' "${MAPPING}" &&
  ! grep -qE '^org\.indra\.android\.core\.child\.ChildProcess -> org\.indra\.android\.core\.child\.ChildProcess:' "${MAPPING}"; then
  echo "ok   minification is active (un-kept control class was renamed)"
else
  echo "WARN control class was not renamed; check minifyEnabled for :indra release builds" >&2
fi

if [ "${STATUS}" -ne 0 ]; then
  echo "" >&2
  echo "The release APK would ship without a working native event pipeline." >&2
  echo "Add the missing keeps to indra/proguard-rules.pro." >&2
  exit 1
fi

echo "R8 keeps verified in ${MAPPING}"
