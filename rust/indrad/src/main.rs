mod children;
mod nmap;
mod protocol;

use children::{kill_script, parse_signal, ChildTable};
use protocol::{code, Message, CONTROL_ID};
use std::os::unix::process::ExitStatusExt;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixListener;
use tokio::process::Command;
use tokio::sync::{Mutex, Semaphore};

fn socket_path() -> String {
    std::env::var("INDRAD_SOCKET").unwrap_or_else(|_| "/data/user/0/org.indra.android/files/indrad.sock".to_string())
}

/// One connection may have at most this many tools alive at once. Beyond it a start is
/// refused instead of forking an unbounded tree of root processes.
const MAX_CHILDREN_PER_CONNECTION: usize = 8;

/// How long a killed child's output readers may keep draining after the process is gone.
/// A grandchild that inherited the pipes can hold them open forever (`sh -c 'tool &'`), so
/// this is a grace period, not a wait for EOF.
const DRAIN_GRACE_SECS: u64 = 2;

/// How long the descendant-kill script may take before the direct `kill(2)` fallback runs.
const KILL_TIMEOUT_SECS: u64 = 3;

// Handler definitions: (id, have_stdin, have_stdout, name, binary_path, workdir)
const HANDLERS: &[(u8, bool, bool, &str, &str, &str)] = &[
    (0, false, true, "nmap", "tools/nmap/nmap", "."),
    (1, true, true, "hydra", "tools/hydra/hydra", "."),
    (2, false, true, "masscan", "tools/masscan/masscan", "."),
    (3, false, true, "reaver", "tools/reaver/reaver", "."),
    (4, true, true, "hping3", "tools/hping3/hping3", "."),
    (5, false, true, "nuclei", "tools/nuclei/nuclei", "."),
    (6, true, true, "bettercap", "tools/bettercap/bettercap", "."),
    (7, false, true, "arpspoof", "tools/arpspoof/arpspoof", "."),
    (8, false, true, "tcpdump", "tools/tcpdump/tcpdump", "."),
    (9, true, true, "ettercap", "tools/ettercap/ettercap", "."),
    (10, false, true, "aircrack-ng", "tools/aircrack-ng/aircrack-ng", "."),
    (11, false, true, "fusemounts", "tools/fusemounts/fusemounts", "."),
    (12, false, true, "network-radar", "tools/network-radar/network-radar", "."),
    (13, false, true, "msfrpcd", "tools/msfrpcd/msfrpcd", "."),
    (14, true, true, "shell", "/system/bin/sh", "."),
    (15, true, true, "raw", "", "."),
    (16, false, true, "blind", "", "."),
];

type Writer = Arc<Mutex<tokio::net::unix::OwnedWriteHalf>>;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let sp = socket_path();
    let _ = std::fs::remove_file(&sp);

    let listener = UnixListener::bind(&sp)?;
    println!("indrad-rust: listening on {}", sp);

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&sp, std::fs::Permissions::from_mode(0o666));
    }

    let max_connections = std::sync::Arc::new(Semaphore::new(16));
    loop {
        match listener.accept().await {
            Ok((stream, _addr)) => {
                let permit = max_connections.clone().acquire_owned().await.unwrap();
                tokio::spawn(async move {
                    if let Err(e) = handle_client(stream).await {
                        eprintln!("client error: {}", e);
                    }
                    drop(permit);
                });
            }
            Err(e) => {
                eprintln!("accept error: {}", e);
            }
        }
    }
}


/// Read the users file and verify credentials (constant-time comparison).
fn verify_credential(user: &str, hash: &str) -> bool {
    let users_path = std::env::var("INDRAD_CORE_DIR")
        .unwrap_or_else(|_| "/data/user/0/org.indra.android/files".to_string())
        + "/users";
    let content = match std::fs::read_to_string(&users_path) {
        Ok(c) => c,
        Err(_) => {
            eprintln!("cannot read users file: {}", users_path);
            return false;
        }
    };
    for line in content.lines() {
        let parts: Vec<&str> = line.splitn(2, ':').collect();
        if parts.len() != 2 { continue; }
        let stored_user = parts[0];
        let stored_hash = parts[1];
        // Constant-time comparison (no short-circuit)
        let user_match = constant_time_eq(user.as_bytes(), stored_user.as_bytes());
        let hash_match = constant_time_eq(hash.as_bytes(), stored_hash.as_bytes());
        if user_match && hash_match {
            return true;
        }
    }
    false
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() { return false; }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

async fn handle_client(stream: tokio::net::UnixStream) -> anyhow::Result<()> {
    // SO_PEERCRED: verify the peer is an allowed uid
    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;
        let fd = stream.as_raw_fd();
        let mut cred: libc::ucred = unsafe { std::mem::zeroed() };
        let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        let ret = unsafe {
            libc::getsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_PEERCRED,
                &mut cred as *mut _ as *mut libc::c_void,
                &mut len,
            )
        };
        if ret == 0 {
            eprintln!("peer uid={} gid={} pid={}", cred.uid, cred.gid, cred.pid);
            // Check against INDRA_ALLOWED_UID env var (if set)
            if let Ok(allowed) = std::env::var("INDRA_ALLOWED_UID") {
                if let Ok(allowed_uid) = allowed.parse::<u32>() {
                    if cred.uid != allowed_uid {
                        eprintln!("SO_PEERCRED: uid {} not allowed (expected {}), rejecting", cred.uid, allowed_uid);
                        // Reject the connection
                        return Ok(());
                    }
                }
            }
        } else {
            eprintln!("SO_PEERCRED failed: {}", std::io::Error::last_os_error());
            // Don't reject on failure (could be non-Linux) — just log
        }
    }

    let (mut reader, writer) = stream.into_split();
    let writer = Arc::new(Mutex::new(writer));

    // Auth (with timeout)
    let msg = match tokio::time::timeout(
        std::time::Duration::from_secs(10),
        read_message(&mut reader),
    ).await {
        Ok(Ok(Some(m))) => m,
        Ok(Ok(None)) => return Ok(()),
        Ok(Err(e)) => return Err(e.into()),
        Err(_) => {
            eprintln!("auth timeout");
            return Ok(());
        }
    };

    if msg.id != CONTROL_ID || msg.body.is_empty() || msg.body[0] != code::AUTH {
        send_auth_fail(&writer, msg.seq).await?;
        return Ok(());
    }

    // Parse auth body: [AUTH] [user\0] [hash\0]
    let mut pos = 1;
    let user = if pos < msg.body.len() {
        let end = msg.body[pos..].iter().position(|&b| b == 0).unwrap_or(msg.body.len() - pos);
        let s = String::from_utf8_lossy(&msg.body[pos..pos + end]).to_string();
        pos += end + 1;
        s
    } else {
        String::new()
    };
    let hash = if pos < msg.body.len() {
        let end = msg.body[pos..].iter().position(|&b| b == 0).unwrap_or(msg.body.len() - pos);
        String::from_utf8_lossy(&msg.body[pos..pos + end]).to_string()
    } else {
        String::new()
    };

    eprintln!("AUTH attempt: user={}", user);

    if verify_credential(&user, &hash) {
        send_auth_ok(&writer, msg.seq).await?;
    } else {
        eprintln!("AUTH failed for user={}", user);
        send_auth_fail(&writer, msg.seq).await?;
        return Ok(());
    }

    let children = Arc::new(Mutex::new(ChildTable::new()));
    let sessions = serve(&mut reader, &writer, &children).await;
    // Whatever ended the loop (the client went away, or DMON_STOP), a tool started by this
    // connection is useless without it. A root process that outlives its client is exactly
    // the orphan this daemon used to leave behind.
    teardown(&children).await;
    sessions
}

/// The authenticated control loop.
///
/// Kept separate from [`handle_client`] so the child table has one teardown path: every early
/// return, and a normal disconnect, end up in the same cleanup.
async fn serve(
    reader: &mut tokio::net::unix::OwnedReadHalf,
    writer: &Writer,
    children: &Arc<Mutex<ChildTable>>,
) -> anyhow::Result<()> {
    loop {
        let msg = match read_message(reader).await? {
            Some(m) => m,
            None => break,
        };

        if msg.id != CONTROL_ID || msg.body.is_empty() {
            continue;
        }

        let ctrl_code = msg.body[0];
        match ctrl_code {
            code::HNDL_LIST => {
                handle_handler_list(writer, msg.seq).await?;
            }
            code::CMD_START => {
                handle_cmd_start(writer, msg, children).await?;
            }
            code::CMD_SIGNAL => {
                handle_cmd_signal(msg, children).await;
            }
            code::DMON_STOP => {
                break;
            }
            _ => {
                eprintln!("unhandled control code: {}", ctrl_code);
            }
        }
    }

    Ok(())
}



async fn send_auth_ok<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, seq: u16) -> anyhow::Result<()> {
    let msg = Message::control(seq, code::AUTH_OK, vec![]);
    write_message(writer, &msg).await
}

async fn send_auth_fail<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, seq: u16) -> anyhow::Result<()> {
    let msg = Message::control(seq, code::AUTH_FAIL, vec![]);
    write_message(writer, &msg).await
}

async fn send_cmd_fail<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, seq: u16, reason: &str) -> anyhow::Result<()> {
    let mut body = vec![code::CMD_FAIL];
    body.extend_from_slice(reason.as_bytes());
    body.push(0);
    let msg = Message::new(seq, CONTROL_ID, body);
    write_message(writer, &msg).await
}

async fn handle_handler_list<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, seq: u16) -> anyhow::Result<()> {
    let mut body = vec![code::HNDL_LIST];
    for (id, have_stdin, have_stdout, name, _, _) in HANDLERS {
        body.push(*id);
        let mut flags = 0u8;
        if *have_stdin { flags |= 0x01; }
        if *have_stdout { flags |= 0x02; }
        body.push(flags);
        body.extend_from_slice(name.as_bytes());
        body.push(0);
    }
    let msg = Message::new(seq, CONTROL_ID, body);
    write_message(writer, &msg).await
}

async fn handle_cmd_start(writer: &Writer, msg: Message, children: &Arc<Mutex<ChildTable>>) -> anyhow::Result<()> {
    let seq = msg.seq;
    eprintln!("CMD_START: seq={} body_len={} handler_id={}", seq, msg.body.len(), if msg.body.len() >= 2 { msg.body[1] } else { 255 });

    if msg.body.len() < 3 {
        send_cmd_fail(writer, seq, "short CMD_START message").await?;
        return Ok(());
    }

    let handler_id = msg.body[1];

    let handler = HANDLERS.iter().find(|h| h.0 == handler_id);
    if handler.is_none() {
        send_cmd_fail(writer, seq, "handler not found").await?;
        return Ok(());
    }
    let (_, _have_stdin, _have_stdout, name, binary, workdir) = handler.unwrap();

    // Parse argv
    let argv_count = msg.body[2] as usize;
    let mut pos = 3usize;
    let mut argv: Vec<String> = Vec::new();
    for _ in 0..argv_count {
        if pos >= msg.body.len() { break; }
        let end = msg.body[pos..].iter().position(|&b| b == 0).unwrap_or(msg.body.len() - pos);
        let s = String::from_utf8_lossy(&msg.body[pos..pos + end]).to_string();
        argv.push(s);
        pos += end + 1;
    }

    // Parse env
    let mut env: Vec<String> = Vec::new();
    while pos < msg.body.len() {
        if msg.body[pos] == 0 { pos += 1; continue; }
        let end = msg.body[pos..].iter().position(|&b| b == 0).unwrap_or(msg.body.len() - pos);
        if end == 0 { break; }
        let s = String::from_utf8_lossy(&msg.body[pos..pos + end]).to_string();
        env.push(s);
        pos += end + 1;
    }

    // Set argv[0] to binary path
    if !binary.is_empty() && !argv.is_empty() {
        argv[0] = binary.to_string();
    }

    if argv.is_empty() || argv[0].is_empty() {
        send_cmd_fail(writer, seq, "no command").await?;
        return Ok(());
    }

    let core_dir = "/data/user/0/org.indra.android/files";
    let work_dir = format!("{}/{}", core_dir, workdir);

    // Build command
    let mut cmd = Command::new(&argv[0]);
    for arg in &argv[1..] {
        cmd.arg(arg);
    }
    cmd.current_dir(&work_dir);
    cmd.env("LD_LIBRARY_PATH", format!("{}/lib", core_dir));

    for e in &env {
        if let Some((k, v)) = e.split_once('=') {
            cmd.env(k, v);
        }
    }

    cmd.stdout(std::process::Stdio::piped());
    cmd.stderr(std::process::Stdio::piped());

    if children.lock().await.len() >= MAX_CHILDREN_PER_CONNECTION {
        send_cmd_fail(writer, seq, "too many running children").await?;
        return Ok(());
    }

    eprintln!("spawning: {} {:?}", argv[0], &argv[1..]);
    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(e) => {
            eprintln!("spawn failed: {}", e);
            send_cmd_fail(writer, seq, &format!("exec failed: {}", e)).await?;
            return Ok(());
        }
    };

    let pid = match child.id() {
        Some(pid) => pid,
        None => {
            let _ = child.start_kill();
            send_cmd_fail(writer, seq, "child exited before it started").await?;
            return Ok(());
        }
    };

    let child_id = {
        let mut table = children.lock().await;
        table.allocate().map(|id| {
            table.register(id, pid);
            id
        })
    };
    let Some(child_id) = child_id else {
        let _ = child.start_kill();
        send_cmd_fail(writer, seq, "no free child id").await?;
        return Ok(());
    };

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let is_nmap = *name == "nmap";

    // CMD_STARTED first, so the client's bounded `awaitStart` returns immediately…
    let mut started_body = vec![code::CMD_STARTED, 0u8];
    started_body.extend_from_slice(&u16::to_le_bytes(child_id));
    write_message(writer, &Message::new(seq, CONTROL_ID, started_body)).await?;

    // …then the child is driven by its own task. This function used to await the child to
    // completion, so the control loop could not read CMD_SIGNAL until the tool it was meant
    // to stop had already exited: Stop was unreachable by construction, and a second start
    // on the same connection waited out the whole first run (and failed at START_TIMEOUT).
    let writer = Arc::clone(writer);
    let children = Arc::clone(children);
    tokio::spawn(async move {
        pipe_child(child, child_id, is_nmap, stdout, stderr, writer, children).await;
    });

    Ok(())
}

/// Streams one child's stdout/stderr and reports its exit, in its own task.
///
/// The old version read both pipes to EOF and only then sent every line, under a single
/// 120 s bound whose timeout path returned **empty buffers** — so any run longer than two
/// minutes lost its entire output, and every console stayed blank until the tool exited.
async fn pipe_child(
    mut child: tokio::process::Child,
    child_id: u16,
    is_nmap: bool,
    stdout: Option<tokio::process::ChildStdout>,
    stderr: Option<tokio::process::ChildStderr>,
    writer: Writer,
    children: Arc<Mutex<ChildTable>>,
) {
    let mut out_task = tokio::spawn(stream_stdout(stdout, child_id, is_nmap, writer.clone()));
    let mut err_task = tokio::spawn(stream_stderr(stderr, child_id, writer.clone()));

    // Wait for the process, not for EOF: a grandchild can hold the pipes open forever
    // (`sh -c 'tool &'`), which is how a finished tool used to hang its own completion.
    let status = child.wait().await;

    let drained = tokio::time::timeout(
        std::time::Duration::from_secs(DRAIN_GRACE_SECS),
        async {
            let _ = (&mut out_task).await;
            let _ = (&mut err_task).await;
        },
    )
    .await;
    if drained.is_err() {
        out_task.abort();
        err_task.abort();
    }

    {
        let mut table = children.lock().await;
        table.remove(child_id);
    }

    let end = match status {
        Ok(status) => match (status.code(), status.signal()) {
            (Some(exit_code), _) => {
                let mut body = vec![code::CMD_END, 0u8];
                body.extend_from_slice(&u16::to_le_bytes(child_id));
                body.extend_from_slice(&u32::to_le_bytes(exit_code as u32));
                Message::new(0, CONTROL_ID, body)
            }
            (None, Some(signal)) => died_message(child_id, signal),
            _ => died_message(child_id, 9),
        },
        Err(_) => died_message(child_id, 9),
    };
    let _ = write_message(&writer, &end).await;
}

async fn stream_stdout(
    stdout: Option<tokio::process::ChildStdout>,
    child_id: u16,
    is_nmap: bool,
    writer: Writer,
) {
    let Some(stdout) = stdout else { return };
    let mut lines = BufReader::new(stdout).lines();
    loop {
        match lines.next_line().await {
            // A 0-length body tears the client's reader down (`size == 0`), so blank lines
            // are skipped — they carry no information for either parser.
            Ok(Some(line)) => {
                let body = if is_nmap {
                    match nmap::parse_line(&line) {
                        Some(body) => body,
                        None => continue, // nmap chatter that is not an event
                    }
                } else if line.is_empty() {
                    continue;
                } else {
                    line.into_bytes()
                };
                if body.is_empty() {
                    continue;
                }
                if write_message(&writer, &Message::new(0, child_id, body)).await.is_err() {
                    return;
                }
            }
            Ok(None) | Err(_) => return,
        }
    }
}

async fn stream_stderr(
    stderr: Option<tokio::process::ChildStderr>,
    child_id: u16,
    writer: Writer,
) {
    let Some(stderr) = stderr else { return };
    let mut lines = BufReader::new(stderr).lines();
    loop {
        match lines.next_line().await {
            Ok(Some(line)) => {
                let mut body = vec![code::CMD_STDERR, 0u8];
                body.extend_from_slice(&u16::to_le_bytes(child_id));
                body.extend_from_slice(line.as_bytes());
                body.push(0);
                if write_message(&writer, &Message::new(0, CONTROL_ID, body)).await.is_err() {
                    return;
                }
            }
            Ok(None) | Err(_) => return,
        }
    }
}

fn died_message(child_id: u16, signal: i32) -> Message {
    let mut body = vec![code::CMD_DIED, 0u8];
    body.extend_from_slice(&u16::to_le_bytes(child_id));
    body.extend_from_slice(&u32::to_le_bytes(signal as u32));
    Message::new(0, CONTROL_ID, body)
}

/// Delivers a signal to a child and its descendants.
///
/// The walk runs as a `sh` script because the daemon cannot enumerate another process's
/// children through a syscall. It is bounded — a `ps` that hangs must not park the connection —
/// and the direct `kill(2)` fallback still reaches the process the signal was meant for.
async fn kill_pid(pid: u32, signal: i32) {
    let script = kill_script(pid, signal);
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(KILL_TIMEOUT_SECS),
        Command::new("sh").arg("-c").arg(&script).status(),
    )
    .await;
    unsafe {
        libc::kill(pid as i32, signal);
    }
}

async fn handle_cmd_signal(msg: Message, children: &Arc<Mutex<ChildTable>>) {
    let Some((child_id, signal)) = parse_signal(&msg.body) else {
        return;
    };

    let pid = {
        let table = children.lock().await;
        table.pid(child_id)
    };
    match pid {
        Some(pid) => {
            eprintln!("CMD_SIGNAL: child={} pid={} signal={}", child_id, pid, signal);
            kill_pid(pid, signal).await;
        }
        None => eprintln!("CMD_SIGNAL: child={} is not running", child_id),
    }
}

/// Kills everything a finished connection started.
async fn teardown(children: &Arc<Mutex<ChildTable>>) {
    let pids = {
        let mut table = children.lock().await;
        table.drain()
    };
    for pid in pids {
        kill_pid(pid, 9).await;
    }
}

async fn write_message<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, msg: &Message) -> anyhow::Result<()> {
    let mut w = writer.lock().await;
    let size = msg.body.len() as u16;
    let mut header = [0u8; 6];
    header[0..2].copy_from_slice(&msg.seq.to_le_bytes());
    header[2..4].copy_from_slice(&size.to_le_bytes());
    header[4..6].copy_from_slice(&msg.id.to_le_bytes());
    w.write_all(&header).await?;
    w.write_all(&msg.body).await?;
    w.flush().await?;
    Ok(())
}

async fn read_message<R: AsyncReadExt + Unpin>(reader: &mut R) -> anyhow::Result<Option<Message>> {
    let mut header = [0u8; 6];
    match reader.read_exact(&mut header).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }

    let seq = u16::from_le_bytes([header[0], header[1]]);
    let size = u16::from_le_bytes([header[2], header[3]]);
    let id = u16::from_le_bytes([header[4], header[5]]);

    if size == 0 {
        return Err(anyhow::anyhow!("received 0-length message"));
    }

    let mut body = vec![0u8; size as usize];
    reader.read_exact(&mut body).await?;

    Ok(Some(Message { seq, id, body }))
}
