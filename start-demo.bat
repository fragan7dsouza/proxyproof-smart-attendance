@echo off
rem One-click demo: starts the local Firebase backend with the saved demo data, then boots the Android emulator.
rem The data resets to the saved copy on every start, so a messy demo never sticks.
set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%"
cd /d "%~dp0"

rem Demo copy of the security rules without the QR-freshness check (the emulator can't scan a QR).
rem Every other server check stays. The real firestore.rules is never changed.
powershell -NoProfile -Command "(Get-Content -Raw firestore.rules) -replace '(?s)\s*// QR-CHECK-BEGIN.*?// QR-CHECK-END','' | Set-Content -NoNewline firestore.demo.rules"
powershell -NoProfile -Command "(Get-Content -Raw firebase.json) -replace 'firestore.rules','firestore.demo.rules' | Set-Content -NoNewline firebase.demo.json"

cd rules-test
start "ProxyProof backend - keep this open" cmd /k npx firebase emulators:start --only auth,firestore --project demo-proxyproof --config ../firebase.demo.json --import ../demo-data
start "" "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_37.0
echo.
echo Backend and emulator are starting. Wait for "All emulators ready" in the backend window.
echo Then open the ProxyProof app on the emulator (PIN 1234 if it asks).
timeout /t 8 >nul
