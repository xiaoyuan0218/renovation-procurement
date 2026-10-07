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
                Ok(port) => {
                    let url = format!("http://127.0.0.1:{port}");
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

/// 启动打包进来的后端，等它把实际端口写进数据目录，返回那个端口。
fn start_backend(app: &tauri::App) -> Result<u16, Box<dyn std::error::Error>> {
    let data_dir = app.path().app_data_dir()?;
    std::fs::create_dir_all(&data_dir)?;
    let port_file = data_dir.join("port");

    // 上一次没退干净的实例还活着就直接用它。
    //
    // 从前不管这个：僵尸后端占着端口，新起的那个可能起不来，用户看到的就是
    // 「启动出错、窗口空白」，而且残留进程越积越多。复用已有实例既救了这个
    // 场景，也省掉一次冷启动等待。
    if let Ok(text) = std::fs::read_to_string(&port_file) {
        if let Ok(port) = text.trim().parse::<u16>() {
            if port_alive(port) {
                log_line(app, &format!("复用已在运行的内置服务，端口 {port}"));
                return Ok(port);
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
        .env("RENOVATION_LOCAL_NO_AUTH", "1")
        .spawn()
        .map_err(|error| format!("内置服务启动不了：{error}"))?;
    app.manage(ServerProcess(Mutex::new(Some(child))));

    // PyInstaller 首次启动要解压、SQLite 要初始化，给足时间
    let deadline = Instant::now() + Duration::from_secs(90);
    while Instant::now() < deadline {
        if let Ok(text) = std::fs::read_to_string(&port_file) {
            if let Ok(port) = text.trim().parse::<u16>() {
                log_line(app, &format!("内置服务已就绪，端口 {port}"));
                return Ok(port);
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
