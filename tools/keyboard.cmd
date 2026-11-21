@echo off
rem AOS Keyboard over ADB: install the last build, turn the keyboard on, make it the default and open a text field.
rem   tools\keyboard.cmd          install + enable + set + open the test field
rem   tools\keyboard.cmd open     only open the test field (keyboard pops up)
rem   tools\keyboard.cmd gboard   switch back to Gboard
setlocal
set "ROOT=%~dp0.."
set "ADB=%USERPROFILE%\Android\Sdk\platform-tools\adb.exe"
set "IME=org.anthezis.ahome/org.anthezis.launcher.AosKeyboardService"
set "GBOARD=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
if /i "%1"=="gboard" ( "%ADB%" shell ime set %GBOARD% & exit /b )
if /i "%1"=="open" goto open
"%ADB%" install -r -d "%ROOT%\app\build\outputs\apk\debug\app-debug.apk" || exit /b 1
"%ADB%" shell ime enable %IME%
"%ADB%" shell ime set %IME%
:open
"%ADB%" shell am start -n org.anthezis.ahome/org.anthezis.launcher.KeyboardTestActivity
