// Windows 桌面端外壳：拉起内置的 Python 后端，再把窗口指向它。
//
// 窗口加载的是 http://127.0.0.1:<port> 而不是本地文件 —— 前端与后端同源，
// Cookie 登录态、/assets 绝对路径、导入导出的下载链接全都照常工作，网页版
// 那套代码一行都不用改。
//
// 后端是 PyInstaller 打包出来的独立进程，跟着安装包一起装进 resources。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::Write;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::path::BaseDirectory;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_shell::process::CommandChild;
use tauri_plugin_shell::ShellExt;

/// 内置后端进程。窗口关掉时要把它一起收走，否则下次启动端口还被占着。
struct ServerProcess(Mutex<Option<CommandChild>>);

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            match start_backend(app) {
                Ok((port, stale_note)) => {
                    let mut url = format!("http://127.0.0.1:{port}");
                    if let Some(note) = stale_note {
                        // 内核版本对不上时的说明交给界面显示（AppShell 读这个参数）
                        url.push_str(&format!("/?service_stale={}", percent_encode(&note)));
                    }
                    WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url.parse()?))
                        .title("采知道")
                        .inner_size(1280.0, 860.0)
                        .min_inner_size(900.0, 620.0)
                        .build()?;
                }
                Err(error) => {
                    // 起不来也要把话说清楚：原因写进数据目录的 startup.log，并在
                    // 窗口里显示出来。白屏或静默退出，用户只能干瞪眼。
                    let message = error.to_string();
                    log_line(app, &format!("启动失败：{message}"));
                    show_error_window(app, &message)?;
                }
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("桌面外壳启动失败")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                stop_backend(app);
            }
        });
}

/// 端口上有没有东西在监听（用来判断上一次的后端是不是还活着）。
fn port_alive(port: u16) -> bool {
    std::net::TcpStream::connect_timeout(
        &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
        Duration::from_millis(600),
    )
    .is_ok()
}

/// 端口上那个服务自报的版本（问一句 `/api/version`）。问不到返回 None。
///
/// 只用来回答一个问题：它是**本版本**的内置服务吗（见 start_backend 里的说明）。
/// 请求目标固定（本机回环、固定路径），所以手写一个极简 HTTP 就够，不引依赖。
fn server_version(port: u16) -> Option<String> {
    use std::io::Read;

    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    let mut stream =
        std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(800)).ok()?;
    stream.set_read_timeout(Some(Duration::from_millis(1500))).ok()?;
    stream
        .write_all(b"GET /api/version HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .ok()?;

    let mut raw = Vec::new();
    stream.take(64 * 1024).read_to_end(&mut raw).ok()?;
    let text = String::from_utf8_lossy(&raw);
    let body = text.split("\r\n\r\n").nth(1)?;

    // 只取 `"version":"x.y.z"` 这一段，不为这一处引 JSON 库
    let key = "\"version\":\"";
    let start = body.find(key)? + key.len();
    let end = start + body[start..].find('"')?;
    Some(body[start..end].to_string())
}

/// 启动打包进来的后端，等它把实际端口写进数据目录，返回端口与一条（可选的）提示。
///
/// 提示非空表示：刚起来的这份内核自报的版本与本程序对不上 —— 多半是升级安装时
/// 旧进程占着文件、新内核没换成功（安装钩子会先结束旧进程，这里是兜底），界面
/// 会把它显示出来，别让用户对着"新界面旧内核"干瞪眼。
fn start_backend(app: &tauri::App) -> Result<(u16, Option<String>), Box<dyn std::error::Error>> {
    let data_dir = app.path().app_data_dir()?;
    std::fs::create_dir_all(&data_dir)?;
    let port_file = data_dir.join("port");

    // 上一次没退干净的实例还活着就直接用它 —— 但**先确认它是本版本的服务**。
    //
    // 从前只看端口活着就复用，升级安装时踩过一个很难查的坑：旧版
    // caizhidao-server.exe 要是在运行（安装器只盯着主程序，不认识这个独立
    // 进程），它占着文件、安装器会跳过替换；新主程序一看端口活着就复用了它 ——
    // 界面是新的、后端是旧的，新版本加的字段（比如采购记录的定金）会被旧后端
    // 静默丢掉，用户看到的是"改了没生效"，而新装、镜像部署都正常。
    // 所以问一句 /api/version：对不上就不复用它，另起本版本的服务。
    if let Ok(text) = std::fs::read_to_string(&port_file) {
        if let Ok(port) = text.trim().parse::<u16>() {
            if port_alive(port) {
                let want = app.package_info().version.to_string();
                match server_version(port) {
                    Some(found) if found == want => {
                        log_line(app, &format!("复用已在运行的内置服务，端口 {port}"));
                        return Ok((port, None));
                    }
                    Some(found) => log_line(
                        app,
                        &format!("端口 {port} 上是旧版内置服务（{found}，本程序 {want}）：不复用，另起一个"),
                    ),
                    None => log_line(app, &format!("端口 {port} 被别的程序占着：另起内置服务")),
                }
            }
        }
    }

    let dist = app.path().resolve("frontend_dist", BaseDirectory::Resource)?;
    log_line(app, &format!("数据目录 {data_dir:?}，前端产物 {dist:?}"));

    // 端口文件先删掉：否则可能读到上一次留下的旧端口
    let _ = std::fs::remove_file(&port_file);

    let (_events, child) = app
        .shell()
        .sidecar("caizhidao-server")?
        .env("RENOVATION_DATA_DIR", data_dir.to_string_lossy().to_string())
        .env("RENOVATION_DIST", dist.to_string_lossy().to_string())
        .env("RENOVATION_HOST", "127.0.0.1")
        .env("RENOVATION_PORT", "8000")
        .spawn()
        .map_err(|error| format!("内置服务启动不了：{error}"))?;
    app.manage(ServerProcess(Mutex::new(Some(child))));

    // PyInstaller 首次启动要解压、SQLite 要初始化，给足时间
    let deadline = Instant::now() + Duration::from_secs(90);
    while Instant::now() < deadline {
        if let Ok(text) = std::fs::read_to_string(&port_file) {
            if let Ok(port) = text.trim().parse::<u16>() {
                log_line(app, &format!("内置服务已就绪，端口 {port}"));
                // 兜底自检：安装时若旧内核占着文件，替换会失败、磁盘上还是旧版本，
                // 这一份起来后自报的版本就对不上。把话说清楚（界面顶部会显示），
                // 否则用户面对的是一堆"改了没生效"，而且每次现象都不一样。
                //
                // 要带重试：内核是"先写端口文件、再去监听"的，刚读到文件时它多半
                // 还没开始接受连接，一次探测必然是空的 —— 那会把"旧内核"漏过去。
                let want = app.package_info().version.to_string();
                let mut found = server_version(port);
                for _ in 0..12 {
                    if found.is_some() {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(500));
                    found = server_version(port);
                }
                let stale = match found {
                    Some(found) if found != want => {
                        log_line(app, &format!("内置服务版本不符：期望 {want}，实际 {found}"));
                        Some(format!(
                            "内置服务没有更新成功（内核 {found}，本程序 {want}）。\
                             请重启一次电脑、再重新安装采知道；在此之前，新功能可能不会生效。"
                        ))
                    }
                    None => {
                        log_line(app, &format!("内置服务版本没问出来（端口 {port}）"));
                        None
                    }
                    _ => None,
                };
                return Ok((port, stale));
            }
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    Err("内置服务 90 秒内没有起来（可能是端口被占用或被安全软件拦截）".into())
}

fn stop_backend(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<ServerProcess>() {
        if let Some(child) = state.0.lock().unwrap().take() {
            let _ = child.kill();
        }
    }
}

/// 往数据目录的 startup.log 追加一行（诊断用；写不进去也不影响启动）。
fn log_line(app: &tauri::App, text: &str) {
    if let Ok(dir) = app.path().app_data_dir() {
        let _ = std::fs::create_dir_all(&dir);
        if let Ok(mut file) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join("startup.log"))
        {
            let _ = writeln!(file, "{text}");
        }
    }
}

/// 启动失败时开一个窗口把原因说清楚，而不是留一片空白。
fn show_error_window(app: &tauri::App, message: &str) -> Result<(), Box<dyn std::error::Error>> {
    let escaped = message.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;");
    let html = format!(
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>采知道</title></head>\
<body style=\"font-family:system-ui,'Microsoft YaHei',sans-serif;padding:28px;line-height:1.8;color:#1f2937\">\
<h2 style=\"margin:0 0 12px\">启动失败</h2>\
<p>内置服务没能起来，程序无法继续。下面是具体原因：</p>\
<pre style=\"white-space:pre-wrap;background:#f3f4f6;padding:12px;border-radius:8px;font-size:13px\">{escaped}</pre>\
<p style=\"color:#6b7280;font-size:13px\">这段信息也写进了数据目录里的 startup.log。</p>\
</body></html>"
    );
    let url = format!("data:text/html;charset=utf-8,{}", percent_encode(&html));
    WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url.parse()?))
        .title("采知道 - 启动失败")
        .inner_size(760.0, 520.0)
        .build()?;
    Ok(())
}

/// data URL 里只允许一部分字符原样出现，其余都得转义。
fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;

    /// 起一个"只会吐一句 JSON"的假服务，验证版本能被读出来。
    fn fake_server(body: &'static str) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut buf = [0u8; 2048];
                let _ = stream.read(&mut buf);
                let response = format!(
                    "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\
                     Content-Length: {}\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
            }
        });
        port
    }

    #[test]
    fn reads_version_from_local_service() {
        // 与真实后端 /api/version 的返回同形（老版本会报自己的版本号）
        let port = fake_server(r#"{"version":"1.2.0","built_at":"","repo":"x/y"}"#);
        assert_eq!(server_version(port).as_deref(), Some("1.2.0"));
    }

    #[test]
    fn missing_service_reads_as_none() {
        // 端口上没有服务（或被别的程序占着）：要返回 None，好让启动逻辑另起服务
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        assert_eq!(server_version(port), None);
    }
}
