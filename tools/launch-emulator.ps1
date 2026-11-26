# Launch the LightEff emulator and pin its window to the top-left of the screen.
# If the emulator is already running, only reposition the window.
$ErrorActionPreference = 'SilentlyContinue'

$avd = 'LightEff'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$emulator = 'D:\Android\Sdk\emulator\emulator.exe'
$appPackage = 'com.qingxiao.lighteff'

Add-Type @'
using System;
using System.Runtime.InteropServices;
public class EmuWindowTool {
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr ctx);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
}
'@

[void][EmuWindowTool]::SetThreadDpiAwarenessContext([IntPtr](-4))

$alreadyRunning = [bool](& $adb devices 2>$null | Select-String 'emulator-\d+\s+device')
if (-not $alreadyRunning) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $emulator
    $psi.Arguments = "-avd $avd -no-snapshot-load -gpu swiftshader_indirect"
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    [void][System.Diagnostics.Process]::Start($psi)
}

# Place the window as soon as it appears, without waiting for Android to finish booting.
$handleValue = 0
$attempts = 0
while ($handleValue -eq 0 -and $attempts -lt 240) {
    $attempts++
    Start-Sleep -Milliseconds 500
    $proc = Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like 'Android Emulator*' } |
        Select-Object -First 1
    if ($null -ne $proc) {
        $handleValue = [long]$proc.MainWindowHandle
    }
}
if ($handleValue -eq 0) { exit 1 }
$handle = [IntPtr]$handleValue

$screenWidth = [EmuWindowTool]::GetSystemMetrics(0)
$screenHeight = [EmuWindowTool]::GetSystemMetrics(1)
$height = [int]($screenHeight * 0.92)
$width = [int]($height * 0.4575)
$x = [int]($screenWidth * 0.007)
$y = 6

function Set-EmuWindow($h) {
    [void][EmuWindowTool]::ShowWindow($h, 9)
    [void][EmuWindowTool]::SetWindowPos($h, [IntPtr](-1), $x, $y, $width, $height, 0x0040)
    [void][EmuWindowTool]::SetForegroundWindow($h)
    [void][EmuWindowTool]::SetWindowPos($h, [IntPtr](-2), $x, $y, $width, $height, 0x0040)
}

Set-EmuWindow $handle

# The emulator may resize itself again while booting, so keep enforcing the geometry.
if (-not $alreadyRunning) {
    for ($i = 0; $i -lt 120; $i++) {
        $booted = & $adb shell getprop sys.boot_completed 2>$null
        if ($booted -match '1') { break }
        if ($i % 5 -eq 0) { Set-EmuWindow $handle }
        Start-Sleep -Seconds 1
    }
    & $adb shell monkey -p $appPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 3
    Set-EmuWindow $handle
}
