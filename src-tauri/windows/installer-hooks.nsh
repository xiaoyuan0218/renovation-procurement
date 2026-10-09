; 安装前把可能还在运行的内置服务结束掉。
;
; 为什么需要：内置服务（caizhidao-server.exe）是个独立进程，安装器只认识主程序；
; 用户上次用完它若没退干净（主程序被强杀、安装时没关它），它就占着文件。Windows
; 不允许覆盖正在运行的程序，安装器会跳过替换 —— 装完界面是新的、内核还是旧的，
; 新版本才有的功能会被旧内核静默丢掉（例如采购记录的定金存不住），而用户完全不
; 知道发生了什么。在拷贝文件之前先结束它，替换就一定成功。
;
; 进程本来就不存在时 taskkill 返回非零，忽略即可（这里不检查返回值）。
!macro NSIS_HOOK_PREINSTALL
  nsExec::ExecToLog 'taskkill /IM caizhidao-server.exe /F'
!macroend

; 卸载前同样先结束它：不然它的程序文件删不掉，会在磁盘上留一份。
!macro NSIS_HOOK_PREUNINSTALL
  nsExec::ExecToLog 'taskkill /IM caizhidao-server.exe /F'
!macroend
