// Wire protocol: 6-byte LE header + body. Mirrors DaemonProtocol.kt exactly.

use std::io::{Read, Write};

pub const CONTROL_ID: u16 = 0;

pub mod code {
    pub const AUTH: u8 = 0;
    pub const AUTH_OK: u8 = 1;
    pub const AUTH_FAIL: u8 = 2;
    pub const CMD_START: u8 = 3;
    pub const CMD_STARTED: u8 = 4;
    pub const CMD_FAIL: u8 = 5;
    pub const CMD_SIGNAL: u8 = 6;
    pub const CMD_END: u8 = 7;
    pub const CMD_DIED: u8 = 8;
    pub const CMD_STDERR: u8 = 9;
    pub const HNDL_LIST: u8 = 10;
    pub const DMON_STOP: u8 = 15;
}

#[derive(Debug, Clone)]
pub struct Message {
    pub seq: u16,
    pub id: u16,
    pub body: Vec<u8>,
}

impl Message {
    pub fn new(seq: u16, id: u16, body: Vec<u8>) -> Self {
        Self { seq, id, body }
    }

    pub fn control(seq: u16, code: u8, body: Vec<u8>) -> Self {
        let mut full_body = vec![code];
        full_body.extend_from_slice(&body);
        Self { seq, id: CONTROL_ID, body: full_body }
    }
}

/// Read one message from a stream. Returns None on clean EOF.
pub fn read_message<R: Read>(reader: &mut R) -> std::io::Result<Option<Message>> {
    let mut header = [0u8; 6];
    match reader.read_exact(&mut header) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }

    let seq = u16::from_le_bytes([header[0], header[1]]);
    let size = u16::from_le_bytes([header[2], header[3]]);
    let id = u16::from_le_bytes([header[4], header[5]]);

    if size == 0 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "received 0-length message",
        ));
    }

    let mut body = vec![0u8; size as usize];
    reader.read_exact(&mut body)?;

    Ok(Some(Message { seq, id, body }))
}

/// Write one message to a stream.
pub fn write_message<W: Write>(writer: &mut W, msg: &Message) -> std::io::Result<()> {
    let size = msg.body.len() as u16;
    let mut header = [0u8; 6];
    header[0..2].copy_from_slice(&msg.seq.to_le_bytes());
    header[2..4].copy_from_slice(&size.to_le_bytes());
    header[4..6].copy_from_slice(&msg.id.to_le_bytes());
    writer.write_all(&header)?;
    writer.write_all(&msg.body)?;
    writer.flush()?;
    Ok(())
}

/// Read a NUL-terminated string from a byte slice starting at offset.
pub fn read_cstr(data: &[u8], offset: usize) -> String {
    let end = data[offset..]
        .iter()
        .position(|&b| b == 0)
        .unwrap_or(data.len() - offset);
    String::from_utf8_lossy(&data[offset..offset + end]).to_string()
}

/// Pack strings as NUL-terminated, back-to-back (str_array format).
pub fn pack_strings(strings: &[&str]) -> Vec<u8> {
    let mut buf = Vec::new();
    for s in strings {
        buf.extend_from_slice(s.as_bytes());
        buf.push(0);
    }
    buf
}

/// Read a string array from a byte slice (NUL-terminated, packed).
pub fn read_string_array(data: &[u8]) -> Vec<String> {
    let mut result = Vec::new();
    let mut pos = 0;
    while pos < data.len() {
        if data[pos] == 0 {
            // Skip empty entries
            pos += 1;
            continue;
        }
        let end = data[pos..]
            .iter()
            .position(|&b| b == 0)
            .unwrap_or(data.len() - pos);
        if end == 0 {
            break;
        }
        let s = String::from_utf8_lossy(&data[pos..pos + end]).to_string();
        result.push(s);
        pos += end + 1;
    }
    result
}
