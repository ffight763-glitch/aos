@echo off
rem Build ahome and push the update (latest.json + ahome.apk) to the public GitHub repo ahome-updates.
rem Phones fetch it from raw.githubusercontent.com, so mobile internet is enough.
rem One-time: run   tools\publish_github.cmd https://github.com/YOUR_NAME/ahome-updates.git
setlocal
set "ROOT=%~dp0.."
pushd "%ROOT%"
set "JAVA_HOME=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"
rem -PaosPublic keeps the YouTube key out of the APK, because this APK goes to a public repository.
rem The shipped APK is a release build (R8, not debuggable), signed with the debug key so updates keep working.
call "%USERPROFILE%\Android\gradle\gradle-8.10.2\bin\gradle.bat" -PaosPublic :app:assembleRelease || exit /b 1
python tools\serve_updates.py publish || exit /b 1
copy /y app\build\outputs\apk\release\app-release.apk ahome.apk >nul
pushd updates
if not exist .git (
  git init -b main
  if "%~1"=="" (echo Pass the repo URL on the first run & exit /b 1)
  git remote add origin %~1
)
git config user.name dozyFJ
git config user.email dozyFJ@users.noreply.github.com
git add -A
git commit -m "ahome update" || echo nothing new
git push -u origin main || exit /b 1
popd
popd
echo Published. Phones will see it on the next update check.
