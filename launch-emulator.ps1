# Generic Android emulator launcher: boot AVD, optionally install an apk, then fit window to screen.
param(
    [string]$Avd = 'LightEff'
)

$ErrorActionPreference = 'SilentlyContinue'

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class EmuWin {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
[EmuWin]::SetProcessDpiAwarenessContext([IntPtr](-4)) | Out-Null

$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$emu = Join-Path $sdk 'emulator\emulator.exe'
$adb = Join-Path $sdk 'platform-tools\adb.exe'

# Any .apk path passed in (e.g. by dropping a file onto the shortcut) is installed after boot.
$apk = @($args) | Where-Object { $_ -like '*.apk' -and (Test-Path $_) } | Select-Object -First 1

# If the requested AVD does not exist, fall back to the first available one.
$avds = @(& $emu -list-avds)
if ($avds -notcontains $Avd) { $Avd = $avds[0] }

Start-Process -FilePath $emu -ArgumentList '-avd', $Avd -WorkingDirectory (Split-Path $emu)

# Wait until Android finishes booting (max 2 minutes).
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    $boot = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($boot -eq '1') { break }
}

function Resize-EmulatorWindow {
    $script:hwnd = [IntPtr]::Zero
    $callback = [EmuWin+EnumWindowsProc] {
        param($h, $l)
        $sb = New-Object System.Text.StringBuilder 256
        [EmuWin]::GetWindowText($h, $sb, 256) | Out-Null
        if ($sb.ToString() -like 'Android Emulator*') { $script:hwnd = $h }
        return $true
    }
    [EmuWin]::EnumWindows($callback, [IntPtr]::Zero) | Out-Null
    if ($script:hwnd -ne [IntPtr]::Zero) {
        [EmuWin]::SetWindowPos($script:hwnd, [IntPtr]::Zero, 20, 10, 432, 860, 0x0040) | Out-Null
        return $true
    }
    return $false
}

# Window size may be reset right after boot, so apply several times.
for ($i = 0; $i -lt 5; $i++) {
    if (Resize-EmulatorWindow) { Start-Sleep -Milliseconds 800 }
}

# Install the apk that was dropped onto the shortcut, if any.
if ($apk) {
    & $adb install -r "$apk" | Out-Null
}
