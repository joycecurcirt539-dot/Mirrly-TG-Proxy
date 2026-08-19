use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

pub const WS_PATH: &str = "/apiws";
pub const WS_PATH_TEST: &str = "/apiws_test";

pub fn default_dc_addr(dc_id: i16) -> SocketAddr {
    let abs_dc = dc_id.abs();
    match abs_dc {
        1 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 175, 50)), 443),
        2 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 167, 51)), 443),
        3 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 175, 100)), 443),
        4 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 167, 91)), 443),
        5 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(91, 108, 56, 130)), 443),
        203 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(91, 105, 192, 100)), 443),
        _ => {
            let normalized = if abs_dc == 0 { 2 } else { ((abs_dc - 1) % 5) + 1 };
            match normalized {
                1 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 175, 50)), 443),
                2 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 167, 51)), 443),
                3 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 175, 100)), 443),
                4 => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 167, 91)), 443),
                _ => SocketAddr::new(IpAddr::V4(Ipv4Addr::new(91, 108, 56, 130)), 443),
            }
        }
    }
}

pub fn get_named_gateway(dc_id: i16) -> &'static str {
    let abs_dc = if dc_id.abs() == 203 { 2 } else { dc_id.abs() };
    match abs_dc {
        1 => "pluto.web.telegram.org",
        2 => "venus.web.telegram.org",
        3 => "aurora.web.telegram.org",
        4 => "vesta.web.telegram.org",
        5 => "flora.web.telegram.org",
        _ => "venus.web.telegram.org",
    }
}

pub fn get_ws_domains(dc_id: i16, is_media: bool) -> Vec<String> {
    let abs_dc = if dc_id.abs() == 203 { 2 } else { dc_id.abs() };
    let dc_num = if abs_dc == 0 || abs_dc > 5 { 2 } else { abs_dc };
    let named = get_named_gateway(dc_num);

    if is_media {
        vec![
            format!("kws{}-1.web.telegram.org", dc_num),
            format!("kws{}.web.telegram.org", dc_num),
            named.to_string(),
        ]
    } else {
        vec![
            format!("kws{}.web.telegram.org", dc_num),
            format!("kws{}-1.web.telegram.org", dc_num),
            named.to_string(),
        ]
    }
}

pub fn get_ws_path(is_test: bool) -> &'static str {
    if is_test {
        WS_PATH_TEST
    } else {
        WS_PATH
    }
}

pub fn find_dc_by_target(host: &str) -> Option<(i16, bool)> {
    let lower = host.trim().to_lowercase();
    match lower.as_str() {
        "149.154.175.50" | "149.154.175.10" => Some((1, false)),
        "149.154.175.51" | "149.154.175.52" => Some((1, true)),
        "149.154.167.51" | "149.154.167.50" | "149.154.167.40" => Some((2, false)),
        "149.154.167.52" | "149.154.167.53" => Some((2, true)),
        "149.154.175.100" | "149.154.175.117" => Some((3, false)),
        "149.154.175.101" => Some((3, true)),
        "149.154.167.91" | "149.154.167.92" => Some((4, false)),
        "149.154.167.93" => Some((4, true)),
        "91.108.56.130" | "91.108.56.165" | "91.108.4.130" => Some((5, false)),
        "91.108.56.131" | "91.108.56.166" => Some((5, true)),
        "91.105.192.100" => Some((203, false)),
        _ => {
            if lower.contains("pluto") {
                Some((1, false))
            } else if lower.contains("venus") {
                Some((2, false))
            } else if lower.contains("aurora") {
                Some((3, false))
            } else if lower.contains("vesta") {
                Some((4, false))
            } else if lower.contains("flora") {
                Some((5, false))
            } else if lower.starts_with("149.154.175.") {
                let last = lower.rsplit('.').next().and_then(|s| s.parse::<i32>().ok()).unwrap_or(50);
                if last >= 100 { Some((3, false)) } else { Some((1, false)) }
            } else if lower.starts_with("149.154.167.") {
                let last = lower.rsplit('.').next().and_then(|s| s.parse::<i32>().ok()).unwrap_or(51);
                if last >= 90 { Some((4, false)) } else { Some((2, false)) }
            } else {
                None
            }
        }
    }
}

pub fn parse_dc_ips(input: &str) -> HashMap<i16, SocketAddr> {
    let mut map = HashMap::new();
    if input.trim().is_empty() {
        return map;
    }

    // Supports format: "1=149.154.175.50:443,2=149.154.167.51:443" or "1:149.154.175.50:443;2:..."
    for item in input.split(|c| c == ',' || c == ';' || c == ' ') {
        let item = item.trim();
        if item.is_empty() {
            continue;
        }

        let parts: Vec<&str> = item.split(|c| c == '=' || c == ':').collect();
        if parts.len() >= 2 {
            if let Ok(dc_num) = parts[0].trim().parse::<i16>() {
                let addr_str = parts[1..].join(":");
                if let Ok(addr) = addr_str.trim().parse::<SocketAddr>() {
                    map.insert(dc_num, addr);
                } else if let Ok(ip) = addr_str.trim().parse::<IpAddr>() {
                    map.insert(dc_num, SocketAddr::new(ip, 443));
                }
            }
        }
    }

    map
}

pub fn resolve_dc_addr(dc_id: i16, custom_map: &HashMap<i16, SocketAddr>) -> SocketAddr {
    if let Some(addr) = custom_map.get(&dc_id) {
        *addr
    } else if let Some(addr) = custom_map.get(&dc_id.abs()) {
        *addr
    } else {
        default_dc_addr(dc_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_dc_addrs() {
        assert_eq!(default_dc_addr(1), "149.154.175.50:443".parse().unwrap());
        assert_eq!(default_dc_addr(2), "149.154.167.51:443".parse().unwrap());
        assert_eq!(default_dc_addr(3), "149.154.175.100:443".parse().unwrap());
        assert_eq!(default_dc_addr(4), "149.154.167.91:443".parse().unwrap());
        assert_eq!(default_dc_addr(5), "91.108.56.130:443".parse().unwrap());
        assert_eq!(default_dc_addr(203), "91.105.192.100:443".parse().unwrap());
        assert_eq!(default_dc_addr(-203), "91.105.192.100:443".parse().unwrap());
    }

    #[test]
    fn test_get_ws_domains() {
        let d2 = get_ws_domains(2, false);
        assert_eq!(d2[0], "kws2.web.telegram.org");
        assert_eq!(d2[1], "kws2-1.web.telegram.org");
        assert_eq!(d2[2], "venus.web.telegram.org");

        let d2_media = get_ws_domains(2, true);
        assert_eq!(d2_media[0], "kws2-1.web.telegram.org");
        assert_eq!(d2_media[1], "kws2.web.telegram.org");
    }

    #[test]
    fn test_parse_dc_ips() {
        let input = "1=149.154.175.50:443, 5=91.108.56.130:443";
        let map = parse_dc_ips(input);
        assert_eq!(map.len(), 2);
        assert_eq!(resolve_dc_addr(1, &map), "149.154.175.50:443".parse().unwrap());
        assert_eq!(resolve_dc_addr(5, &map), "91.108.56.130:443".parse().unwrap());
        assert_eq!(resolve_dc_addr(2, &map), "149.154.167.51:443".parse().unwrap());
    }
}
