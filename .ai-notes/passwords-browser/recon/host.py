#!/usr/bin/env python3
import sys, json, struct, os, time
log = open(os.environ.get("PROBE_LOG") or ("/tmp/nm-probe/host.log" if os.path.isdir("/tmp/nm-probe") else os.path.expanduser("~/host.log")), "a")
log.write(f"{time.time():.0f} start argv={sys.argv[1:]} ppid={os.getppid()} uid={os.getuid()}\n"); log.flush()
while True:
    h = sys.stdin.buffer.read(4)
    if len(h) < 4: break
    n = struct.unpack("<I", h)[0]
    msg = json.loads(sys.stdin.buffer.read(n))
    log.write(f"recv {json.dumps(msg)}\n"); log.flush()
    out = json.dumps({"pong": msg, "home": os.path.expanduser("~"), "ssh": os.path.exists(os.path.expanduser("~/.ssh")), "keyReadable": os.access(os.path.expanduser("~/.ssh/id_ed25519"), os.R_OK), "env_snap": os.environ.get("SNAP", "")}).encode()
    sys.stdout.buffer.write(struct.pack("<I", len(out)) + out); sys.stdout.buffer.flush()
log.write("eof\n")
