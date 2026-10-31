@echo off
rem Build HardRush from source and start a reusable Fabric test server in test-server\<instance>.
rem Windows counterpart of test-server.sh; options are identical (see --help).
setlocal EnableExtensions EnableDelayedExpansion
rem This file and the server console are UTF-8.
chcp 65001 >nul

set "ROOT=%~dp0"
set "INSTANCE=default"
set "MC=26.3"
set "PROPS="

:parse
if "%~1"=="" goto run
set "KEY="
if /i "%~1"=="-h" goto usage
if /i "%~1"=="--help" goto usage
if /i "%~1"=="--instance" set "KEY=Instance"
if /i "%~1"=="--reset" set "KEY=Reset"
if /i "%~1"=="--port" set "KEY=Port"
if /i "%~1"=="--seed" set "KEY=Seed"
if /i "%~1"=="--mode" set "KEY=Mode"
if /i "%~1"=="--memory" set "KEY=Memory"
if /i "%~1"=="--mc" set "KEY=Mc"
if defined KEY (
    if "%~2"=="" (
        echo %~1 需要一个值 1>&2
        exit /b 2
    )
    if "!KEY!"=="Mc" (
        set "MC=%~2"
    ) else (
        if "!KEY!"=="Instance" set "INSTANCE=%~2"
        set PROPS=!PROPS! "-PtestServer!KEY!=%~2"
    )
    shift
    shift
    goto parse
)
set "FLAG="
if /i "%~1"=="--offline" set "FLAG=OnlineMode=false"
if /i "%~1"=="--online" set "FLAG=OnlineMode=true"
if /i "%~1"=="--competition" set "FLAG=Competition=true"
if /i "%~1"=="--no-competition" set "FLAG=Competition=false"
if /i "%~1"=="--no-build" set "FLAG=NoBuild=true"
if defined FLAG (
    set PROPS=!PROPS! "-PtestServer!FLAG!"
    shift
    goto parse
)
echo 未知选项：%~1（--help 查看用法） 1>&2
exit /b 2

:run
set "PLATFORM=mc!MC:.=!"
if not exist "%ROOT%platforms\!PLATFORM!\build.gradle" (
    echo 不支持的游戏版本 !MC!：仓库里没有 platforms\!PLATFORM! 1>&2
    exit /b 2
)
pushd "%ROOT%"
call gradlew.bat ":platforms:!PLATFORM!:prepareTestServer" !PROPS!
if errorlevel 1 (
    popd
    exit /b 1
)
popd
call "%ROOT%test-server\!INSTANCE!\start.cmd"
exit /b %errorlevel%

:usage
echo 用法：test-server.cmd [选项]
echo.
echo   --instance NAME    实例名，默认 default（test-server\NAME）
echo   --reset world      删除存档后启动，保留配置、mods 和白名单
echo   --reset all        实例恢复为全新状态，只保留已下载的原版服务端，不重新下载
echo   --port PORT        服务端端口，默认 25565
echo   --seed SEED        新存档种子（配合 --reset world/all 使用）
echo   --mode MODE        新一轮初始模式 normal ^| hardcore，默认 hardcore
echo   --memory SIZE      最大内存，如 2G、4096M，默认 2G
echo   --offline/--online 关闭／开启正版验证，默认开启
echo   --no-competition   关闭比赛运行时（--competition 重新开启），默认开启
echo   --mc VERSION       游戏版本，对应 platforms\mcXXX，默认 26.3
echo   --no-build         不重新构建，直接用实例里现有的模组启动
echo.
echo 除 --reset、--no-build 外，其余选项会记在实例里，之后不用重复传。
exit /b 0
