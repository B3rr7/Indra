//! Port of the C `nmap` handler's output parser (`cSploitHandlers/nmap.c`).
//!
//! The app routes a child's stdout by the child's **handler name**
//! (`KotlinDaemonClient.dispatchChild`): the `nmap` handler's bytes are fed to
//! `NmapOutputParser`, which decodes the binary structs below — *not* text. So a
//! raw-streaming daemon silently drops every nmap line. This module reproduces the
//! C parsing so the Rust daemon is a faithful drop-in: each matching line becomes the
//! same wire struct the app already decodes.
//!
//! Struct layouts are copied from `cSploitHandlers/nmap.h` (native alignment, LE):
//!
//! ```c
//! struct nmap_port_info    { char action; char proto; uint16_t port; };           // 4 bytes
//! struct nmap_service_info { char action; char proto; uint16_t port; char svc[]; } // strings at 4
//! struct nmap_os_info      { char action; uint8_t accuracy; char os[]; }           // strings at 2
//! struct nmap_hop_info     { char action; uint16_t hop; uint32_t usec;             // __packed__
//!                            uint32_t address; char name[]; }                       // strings at 11
//! ```
//!
//! Actions are `nmap_action` (HOP=0, PORT=1, SERVICE=2, OS=3); protocols are
//! `nmap_proto` (TCP=0, UDP=1, ICMP=2, IGMP=3, UNKNOWN=4) — see `NmapOutputParser`.

pub const ACTION_HOP: u8 = 0;
pub const ACTION_PORT: u8 = 1;
pub const ACTION_SERVICE: u8 = 2;
pub const ACTION_OS: u8 = 3;

const PROTO_TCP: u8 = 0;
const PROTO_UDP: u8 = 1;
const PROTO_ICMP: u8 = 2;
const PROTO_IGMP: u8 = 3;
const PROTO_UNKNOWN: u8 = 4;

/// The order the C `nmap_output_parser` tries its parsers: first match wins.
pub fn parse_line(line: &str) -> Option<Vec<u8>> {
    if line.is_empty() {
        return None;
    }
    parse_hop(line)
        .or_else(|| parse_port(line))
        .or_else(|| parse_xml_port(line))
        .or_else(|| parse_xml_os(line))
}

/// `^Discovered open port ([0-9]+)/([a-z]+)` (case-insensitive).
fn parse_port(line: &str) -> Option<Vec<u8>> {
    let lower = line.to_ascii_lowercase();
    let prefix = "discovered open port ";
    if !lower.starts_with(prefix) {
        return None;
    }
    let rest = &line[prefix.len()..];
    let slash = rest.find('/')?;
    let port: u16 = rest[..slash].trim().parse().ok()?;
    let proto_end = rest[slash + 1..]
        .find(|c: char| !c.is_ascii_alphabetic())
        .map(|i| slash + 1 + i)
        .unwrap_or(rest.len());
    let proto = proto_code(&rest[slash + 1..proto_end]);
    let mut body = Vec::with_capacity(4);
    body.push(ACTION_PORT);
    body.push(proto);
    body.extend_from_slice(&port.to_le_bytes());
    Some(body)
}

/// `<port protocol="…" portid="…"><state state="open"…><service …(product|name)="…"…version="…"`
///
/// Emits a SERVICE struct with `service[] = [name, version]` when a service/name attribute
/// was found, otherwise a bare PORT struct — matching `parse_nmap_xml_port`.
fn parse_xml_port(line: &str) -> Option<Vec<u8>> {
    if !line.contains("<port protocol=") || !line.contains("state=\"open\"") {
        return None;
    }
    let proto = attr(line, "protocol").map(proto_code).unwrap_or(PROTO_UNKNOWN);
    let port: u16 = attr(line, "portid")?.parse().ok()?;
    // `product` first, then `name` — the C regex alternates but either is acceptable.
    let name = attr(line, "product").or_else(|| attr(line, "name"));
    let version = attr(line, "version");

    let mut body = Vec::with_capacity(16);
    match name {
        Some(name) => {
            body.push(ACTION_SERVICE);
            body.push(proto);
            body.extend_from_slice(&port.to_le_bytes());
            body.extend_from_slice(name.as_bytes());
            body.push(0);
            if let Some(version) = version {
                body.extend_from_slice(version.as_bytes());
            }
            body.push(0);
        }
        None => {
            body.push(ACTION_PORT);
            body.push(proto);
            body.extend_from_slice(&port.to_le_bytes());
        }
    }
    Some(body)
}

/// `<osclass type="…" … osfamily="…" … accuracy="…"` → OS struct with `os[] = [osfamily, type]`.
///
/// The C code adds `osfamily` (group 2) first, then `type` (group 1), and the app renders
/// them as `type osfamily (accuracy%)` off `NmapOutputParser.osEvent`.
fn parse_xml_os(line: &str) -> Option<Vec<u8>> {
    if !line.contains("<osclass ") {
        return None;
    }
    let accuracy: u8 = attr(line, "accuracy")?.parse().ok()?;
    let osfamily = attr(line, "osfamily").unwrap_or("unknown");
    let ostype = attr(line, "type").unwrap_or("");

    let mut body = Vec::with_capacity(16);
    body.push(ACTION_OS);
    body.push(accuracy);
    body.extend_from_slice(osfamily.as_bytes());
    body.push(0);
    body.extend_from_slice(ostype.as_bytes());
    body.push(0);
    Some(body)
}

/// `<n>  <time>  [name (]address` — an nmap traceroute hop (packed struct, name at offset 11).
///
/// nmap prints `  1   26.40 ms 10.100.146.162` or, for a named hop,
/// `  3   example.net (93.184.216.34)  5.20 ms`. The time unit is a separate token; the C
/// regex matched `<num>[ m]s` as one group, so accept both shapes.
fn parse_hop(line: &str) -> Option<Vec<u8>> {
    let tokens: Vec<&str> = line.split_whitespace().collect();
    if tokens.len() < 3 {
        return None;
    }
    let hop: u16 = tokens[0].parse().ok()?;

    let (num_str, unit) = if let Some(n) = tokens[1].strip_suffix("ms") {
        (n, "ms")
    } else if let Some(n) = tokens[1].strip_suffix('s') {
        (n, "s")
    } else if tokens.len() > 2 && (tokens[2] == "ms" || tokens[2] == "s") {
        (tokens[1], tokens[2])
    } else {
        (tokens[1], "")
    };
    let usec: u32 = match unit {
        "ms" => (num_str.parse::<f64>().ok()? * 1_000f64) as u32,
        "s" => (num_str.parse::<f64>().ok()? * 1_000_000f64) as u32,
        _ => 0, // `--`, `...`, `... N`: a hop with no measurement
    };

    // The address is the first remaining token that parses as IPv4; a name (if any) is the
    // token immediately before it, printed as `name (`.
    let ip_index = tokens
        .iter()
        .position(|t| t.parse::<std::net::Ipv4Addr>().is_ok())?;
    let ip: std::net::Ipv4Addr = tokens[ip_index].parse().ok()?;
    let name = (ip_index > 0)
        .then(|| tokens[ip_index - 1].strip_suffix('('))
        .flatten();

    let mut body = Vec::with_capacity(16);
    body.push(ACTION_HOP);
    body.extend_from_slice(&hop.to_le_bytes());
    body.extend_from_slice(&usec.to_le_bytes());
    body.extend_from_slice(&u32::from(ip).to_le_bytes());
    if let Some(name) = name {
        body.extend_from_slice(name.as_bytes());
    }
    body.push(0);
    Some(body)
}

/// `name="value"` for the first occurrence of the attribute in an XML fragment.
fn attr<'a>(line: &'a str, name: &str) -> Option<&'a str> {
    let needle = format!("{name}=\"");
    let start = line.find(&needle)? + needle.len();
    let end = line[start..].find('"')? + start;
    Some(&line[start..end])
}

fn proto_code(raw: &str) -> u8 {
    match raw.to_ascii_lowercase().as_str() {
        "tcp" => PROTO_TCP,
        "udp" => PROTO_UDP,
        "icmp" => PROTO_ICMP,
        "igmp" => PROTO_IGMP,
        _ => PROTO_UNKNOWN,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_port_line_becomes_a_port_struct() {
        // nmap -sS -sV -v prints this per open port.
        let body = parse_line("Discovered open port 53/tcp on 127.0.0.1").unwrap();
        assert_eq!(body[0], ACTION_PORT);
        assert_eq!(body[1], PROTO_TCP);
        assert_eq!(u16::from_le_bytes([body[2], body[3]]), 53);
        assert_eq!(body.len(), 4);
    }

    #[test]
    fn xml_port_with_service_becomes_a_service_struct() {
        let line = r#"<port protocol="tcp" portid="22"><state state="open" reason="syn-ack"/><service name="ssh" product="OpenSSH" version="8.9"/>"#;
        let body = parse_line(line).unwrap();
        assert_eq!(body[0], ACTION_SERVICE);
        assert_eq!(body[1], PROTO_TCP);
        assert_eq!(u16::from_le_bytes([body[2], body[3]]), 22);
        assert_eq!(&body[4..], b"OpenSSH\08.9\0");
    }

    #[test]
    fn xml_port_without_service_is_a_bare_port() {
        let line = r#"<port protocol="udp" portid="161"><state state="open"/>"#;
        let body = parse_line(line).unwrap();
        assert_eq!(body[0], ACTION_PORT);
        assert_eq!(body[1], PROTO_UDP);
        assert_eq!(u16::from_le_bytes([body[2], body[3]]), 161);
    }

    #[test]
    fn xml_osclass_becomes_an_os_struct() {
        let line = r#"<osclass type="general purpose" vendor="Linux" osfamily="Linux" osgen="5.X" accuracy="98">"#;
        let body = parse_line(line).unwrap();
        assert_eq!(body[0], ACTION_OS);
        assert_eq!(body[1], 98);
        assert_eq!(&body[2..], b"Linux\0general purpose\0");
    }

    #[test]
    fn unmatched_lines_are_dropped() {
        assert!(parse_line("mass_dns: warning: Unable to open /etc/resolv.conf").is_none());
        assert!(parse_line("Nmap scan report for localhost (127.0.0.1)").is_none());
    }

    #[test]
    fn hop_line_becomes_a_packed_hop_struct() {
        let body = parse_line("  1   26.40 ms 10.100.146.162").unwrap();
        assert_eq!(body[0], ACTION_HOP);
        assert_eq!(u16::from_le_bytes([body[1], body[2]]), 1);
        assert_eq!(u32::from_le_bytes([body[3], body[4], body[5], body[6]]), 26_400);
        assert_eq!(u32::from_le_bytes([body[7], body[8], body[9], body[10]]), u32::from(std::net::Ipv4Addr::new(10, 100, 146, 162)));
    }
}
