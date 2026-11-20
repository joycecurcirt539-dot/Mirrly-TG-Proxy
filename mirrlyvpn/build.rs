fn main() {
    println!("cargo:rerun-if-env-changed=CARGO_CFG_TARGET_OS");
    println!("cargo:rerun-if-env-changed=CARGO_CFG_TARGET_ARCH");

    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();

    if target_os == "android" {
        println!("cargo:rustc-cdylib-link-arg=-Wl,--build-id=sha1");
        if target_arch == "aarch64" || target_arch == "x86_64" {
            println!("cargo:rustc-cdylib-link-arg=-Wl,-z,max-page-size=16384");
        }
    }
}
