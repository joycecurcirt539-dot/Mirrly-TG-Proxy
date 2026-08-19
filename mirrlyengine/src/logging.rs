#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_print(
        prio: std::os::raw::c_int,
        tag: *const std::os::raw::c_char,
        fmt: *const std::os::raw::c_char,
        ...
    ) -> std::os::raw::c_int;
}

pub fn log_info(tag: &str, msg: &str) {
    log_raw(4, tag, msg);
}

pub fn log_warn(tag: &str, msg: &str) {
    log_raw(5, tag, msg);
}

pub fn log_error(tag: &str, msg: &str) {
    log_raw(6, tag, msg);
}

pub fn log_debug(tag: &str, msg: &str) {
    log_raw(3, tag, msg);
}

fn log_raw(level: i32, tag: &str, msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        if let (Ok(c_tag), Ok(c_msg), Ok(c_fmt)) = (
            CString::new(tag),
            CString::new(msg),
            CString::new("%s"),
        ) {
            unsafe {
                __android_log_print(
                    level as std::os::raw::c_int,
                    c_tag.as_ptr(),
                    c_fmt.as_ptr(),
                    c_msg.as_ptr(),
                );
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let prefix = match level {
            3 => "DEBUG",
            4 => "INFO",
            5 => "WARN",
            6 => "ERROR",
            _ => "LOG",
        };
        eprintln!("[{}] [{}] {}", prefix, tag, msg);
    }
}
