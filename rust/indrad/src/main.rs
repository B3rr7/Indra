mod nmap;
mod protocol;

use protocol::{code, Message, CONTROL_ID};
use std::sync::Arc;
use tokio::net::UnixListener;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;
use tokio::sync::{Mutex, Semaphore};
use std::sync::atomic::{AtomicU8, Ordering};

fn socket_path() -> String {
    std::env::var("INDRAD_SOCKET").unwrap_or_else(|_| "/data/user/0/org.indra.android/files/indrad.sock".to_string())
}

static NEXT_CHILD_ID: AtomicU8 = AtomicU8::new(1);

// Track child PIDs for signal delivery
static CHILD_PIDS: std::sync::LazyLock<std::sync::Mutex<std::collections::HashMap<u8, u32>>> = 
    std::sync::LazyLock::new(|| std::sync::Mutex::new(std::collections::HashMap::new()));

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

    loop {
        let msg = match read_message(&mut reader).await? {
            Some(m) => m,
            None => break,
        };

        if msg.id != CONTROL_ID || msg.body.is_empty() {
            continue;
        }

        let ctrl_code = msg.body[0];
        match ctrl_code {
            code::HNDL_LIST => {
                handle_handler_list(&writer, msg.seq).await?;
            }
            code::CMD_START => {
                handle_cmd_start(&writer, msg).await?;
            }
            code::CMD_SIGNAL => {
                handle_cmd_signal(&writer, msg).await?;
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

async fn handle_cmd_start<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, msg: Message) -> anyhow::Result<()> {
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

    eprintln!("spawning: {} {:?}", argv[0], &argv[1..]);
    match cmd.spawn() {
        Ok(mut child) => {
            eprintln!("spawned child pid={:?}", child.id());
            let child_id: u8 = NEXT_CHILD_ID.fetch_add(1, Ordering::Relaxed);
            let stdout = child.stdout.take();
            let stderr = child.stderr.take();

            // Send CMD_STARTED
            let mut started_body = vec![code::CMD_STARTED, 0u8];
            started_body.extend_from_slice(&u16::to_le_bytes(child_id as u16));
            let started_msg = Message::new(seq, CONTROL_ID, started_body);
            write_message(writer, &started_msg).await?;

            // Drain stdout and stderr CONCURRENTLY. Reading one to EOF before the other
            // deadlocks as soon as the tool writes more than one pipe buffer (64 KB) to the
            // other: the tool blocks on the full pipe, so the first stream never reaches EOF.
            // The C daemon solves this with a `poll()` pump in `child.c`; tokio's answer is to
            // await both readers at once. Verified on device: nmap's stderr chatter used to
            // hang the scan into the 30 s bound and read back as "crashed".
            use tokio::io::AsyncReadExt;
            let drain = async {
                let stdout_fut = async {
                    let mut buf = Vec::new();
                    if let Some(mut out) = stdout {
                        let _ = out.read_to_end(&mut buf).await;
                    }
                    buf
                };
                let stderr_fut = async {
                    let mut buf = Vec::new();
                    if let Some(mut err) = stderr {
                        let _ = err.read_to_end(&mut buf).await;
                    }
                    buf
                };
                tokio::join!(stdout_fut, stderr_fut)
            };
            // Bound: a grandchild that inherited the pipes can hold them open forever
            // (`sh -c 'tool &'`), which is the second half of the C daemon's pump fix.
            let (stdout_buf, stderr_buf) = match tokio::time::timeout(
                std::time::Duration::from_secs(120),
                drain,
            )
            .await
            {
                Ok(pair) => pair,
                Err(_) => (Vec::new(), Vec::new()),
            };
            let stdout_data = String::from_utf8_lossy(&stdout_buf).to_string();
            let stderr_data = String::from_utf8_lossy(&stderr_buf).to_string();

            // Send stdout lines as child-routed messages.
            //
            // The app routes stdout by the child's **handler name**
            // (`KotlinDaemonClient.dispatchChild`): the `nmap` handler's bytes go to
            // `NmapOutputParser`, which decodes the binary structs from `nmap.h` — plain text
            // there is silently dropped. Everything else (`raw`, `shell`, `network-radar`, …)
            // is line text.
            //
            // NEVER send a 0-length body: the client's reader rejects `size == 0` outright
            // ("received a 0-length message") and tears down the whole connection, which
            // surfaced on device as `nmap crashed (killed by a signal)` on the first blank
            // line of nmap output. Blank lines carry no information anyway, so skip them.
            let is_nmap = *name == "nmap";
            for line in stdout_data.lines() {
                let body = if is_nmap {
                    match nmap::parse_line(line) {
                        Some(body) => body,
                        None => continue, // non-matching nmap chatter stays on stderr only
                    }
                } else if line.is_empty() {
                    continue;
                } else {
                    line.as_bytes().to_vec()
                };
                let msg = Message::new(0, child_id as u16, body);
                let _ = write_message(writer, &msg).await;
            }

            // Send stderr lines as CMD_STDERR
            for line in stderr_data.lines() {
                let mut body = vec![code::CMD_STDERR, 0u8];
                body.extend_from_slice(&u16::to_le_bytes(child_id as u16));
                body.extend_from_slice(line.as_bytes());
                body.push(0);
                let msg = Message::new(0, CONTROL_ID, body);
                let _ = write_message(writer, &msg).await;
            }

            // Wait for process to exit
            match child.wait().await {
                Ok(status) => {
                    let exit_code = status.code().unwrap_or(-1) as i32;
                    let mut end_body = vec![code::CMD_END, 0u8];
                    end_body.extend_from_slice(&u16::to_le_bytes(child_id as u16));
                    end_body.extend_from_slice(&u32::to_le_bytes(exit_code as u32));
                    let end_msg = Message::new(seq, CONTROL_ID, end_body);
                    // Clean up process tracking
                    if let Ok(mut map) = (*CHILD_PIDS).lock() {
                        map.remove(&child_id);
                    }
                    write_message(writer, &end_msg).await?;
                }
                Err(_) => {
                    // Process died from signal
                    let mut died_body = vec![code::CMD_DIED, 0u8];
                    died_body.extend_from_slice(&u16::to_le_bytes(child_id as u16));
                    died_body.extend_from_slice(&u32::to_le_bytes(9)); // SIGKILL
                    let died_msg = Message::new(seq, CONTROL_ID, died_body);
                    write_message(writer, &died_msg).await?;
                }
            }
        }
        Err(e) => {
            eprintln!("spawn failed: {}", e);
            send_cmd_fail(writer, seq, &format!("exec failed: {}", e)).await?;
        }
    }

    Ok(())
}

async fn handle_cmd_signal<W: AsyncWriteExt + Unpin>(writer: &Arc<Mutex<W>>, msg: Message) -> anyhow::Result<()> {
    // Parse: [CMD_SIGNAL: u8] [padding: u8] [child_id: u16] [signal: u32]
    if msg.body.len() < 8 {
        return Ok(());
    }
    let child_id = msg.body[2]; // u8 child id
    let signal = u32::from_le_bytes([msg.body[4], msg.body[5], msg.body[6], msg.body[7]]);

    eprintln!("CMD_SIGNAL: child={} signal={}", child_id, signal);

    // Find the child PID
    let pid = {
        let map = (*CHILD_PIDS).lock().unwrap();
        map.get(&child_id).copied()
    };

    if let Some(pid) = pid {
        // Kill the process tree: descendants first, then the process
        let script = format!(
            "kill_desc() {{ for c in $(ps -A -o PID,PPID | awk -v p=$1 '$2==p{{print $1}}'); do kill_desc $c; done; kill -{} {} 2>/dev/null; }}; kill_desc {}",
            signal, pid, pid
        );
        let _ = std::process::Command::new("sh")
            .arg("-c")
            .arg(&script)
            .output();
        // Also try direct kill
        unsafe {
            libc::kill(pid as i32, signal as i32);
        }
    }

    Ok(())
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
