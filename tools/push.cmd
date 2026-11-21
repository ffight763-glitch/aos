@echo off
rem Build ahome, publish it to the update server, make every connected phone/emulator reach it.
rem Then on the phone: Manager > Updates > Check for updates. No reinstall needed.
setlocal
set "ROOT=%~dp0.."
set "JAVA_HOME=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ADB=%USERPROFILE%\Android\Sdk\platform-tools\adb.exe"
pushd "%ROOT%"
call "%USERPROFILE%\Android\gradle\gradle-8.10.2\bin\gradle.bat" --no-daemon :app:assembleDebug || exit /b 1
python tools\serve_updates.py publish %* || exit /b 1
copy /y app\build\outputs\apk\debug\app-debug.apk ahome.apk >nul
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do if "%%b"=="device" "%ADB%" -s %%a reverse tcp:8000 tcp:8000
netstat -ano | findstr ":8000 .*LISTENING" >nul || start "ahome updates" /min python tools\serve_updates.py serve
echo Done. Check for updates on the phone.
popd
