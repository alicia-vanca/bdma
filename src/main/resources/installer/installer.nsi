!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"
!include "FileFunc.nsh"
!include "WinMessages.nsh"

!ifndef APP_VERSION
!define APP_VERSION "dev"
!endif

!ifndef INSTALLER_SOURCE_DIR
!define INSTALLER_SOURCE_DIR "dist\\BDMA"
!endif

!ifndef INSTALLER_OUTPUT_DIR
!define INSTALLER_OUTPUT_DIR "."
!endif

!ifndef INSTALLER_ICON
!define INSTALLER_ICON "..\\image\\logo.ico"
!endif

!ifndef APP_DATA_DIR
!define APP_DATA_DIR "$LOCALAPPDATA\bdma"
!endif

!define ACTION_INSTALL_UPDATE "1"
!define ACTION_UNINSTALL "2"
!define ACTION_UNINSTALL_DELETE_DATA "3"

!define MUI_ICON "${INSTALLER_ICON}"
!define MUI_UNICON "${INSTALLER_ICON}"

Name "BDMA"
OutFile "${INSTALLER_OUTPUT_DIR}\\BDMA-${APP_VERSION}-Setup.exe"
Icon "${INSTALLER_ICON}"
UninstallIcon "${INSTALLER_ICON}"
InstallDir "$PROGRAMFILES64\BDMA"
InstallDirRegKey HKLM "Software\BDMA" "InstallDir"
RequestExecutionLevel admin
Unicode True

SetCompressor /SOLID lzma
SetCompressorDictSize 64

!define MUI_ABORTWARNING

Var Dialog
Var RadioInstall
Var RadioUninstall
Var RadioUninstallDelete
Var UserChoice
Var IsInstalled
Var ExistingInstallDir
Var AutoUpdate
Var AutoOpen
Var DelayedCleanupNeeded
Var CleanupLauncherPath
Var PowerShellExe
Var InstallerPid

; ── Action selection page only shown when already installed ─────
Page custom ShowActionDialog ShowActionDialogLeave
!define MUI_PAGE_CUSTOMFUNCTION_SHOW InstFilesPageShow
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\BDMA.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Open BDMA"
!define MUI_FINISHPAGE_TITLE "BDMA Setup Complete"
!define MUI_FINISHPAGE_TEXT "BDMA has been installed successfully."
!define MUI_PAGE_CUSTOMFUNCTION_PRE FinishPagePre
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "English"

; ── Check if already installed ──────────────────────────────────
Function .onInit
  ; Prefer 64-bit PowerShell when available.
  StrCpy $PowerShellExe "$SYSDIR\WindowsPowerShell\v1.0\powershell.exe"
  IfFileExists "$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" 0 powershellReady
  StrCpy $PowerShellExe "$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe"

  powershellReady:
  System::Call 'kernel32::GetCurrentProcessId() i .r0'
  StrCpy $InstallerPid $0

  ; Default action is install/update.
  StrCpy $UserChoice ${ACTION_INSTALL_UPDATE}
  StrCpy $IsInstalled "0"
  StrCpy $ExistingInstallDir ""
  StrCpy $AutoUpdate "0"
  StrCpy $AutoOpen "0"
  StrCpy $DelayedCleanupNeeded "0"
  StrCpy $CleanupLauncherPath ""

  ; Detect app-triggered update mode.
  ${GetParameters} $0
  ${GetOptions} $0 "/BDMA_AUTO_UPDATE" $1
  ${IfNot} ${Errors}
    StrCpy $AutoUpdate "1"
  ${EndIf}

  ; Launch BDMA immediately after a successful install and skip the finish page.
  ${GetOptions} $0 "/BDMA_AUTO_OPEN" $1
  ${IfNot} ${Errors}
    StrCpy $AutoOpen "1"
  ${EndIf}

  ; Keep the install path and the installed flag separate.
  ReadRegStr $ExistingInstallDir HKLM "Software\BDMA" "InstallDir"

  ${If} $ExistingInstallDir == ""
    Goto notInstalled
  ${EndIf}

  IfFileExists "$ExistingInstallDir\BDMA.exe" installed notInstalled

  installed:
    StrCpy $INSTDIR "$ExistingInstallDir"
    StrCpy $IsInstalled "1"
    Return

  notInstalled:
    DeleteRegKey HKLM "Software\BDMA"
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA"
    StrCpy $ExistingInstallDir ""
    StrCpy $IsInstalled "0"
FunctionEnd

; ── Skip the finish page when command-line auto-open was requested ──────────
Function FinishPagePre
  ${If} $AutoOpen == "1"
    Abort
  ${EndIf}
FunctionEnd

; ── Override instfiles page header based on action ──────────────
Function InstFilesPageShow
  SetDetailsView show
  SetDetailsPrint both

  ${If} $UserChoice == ${ACTION_UNINSTALL}
    !insertmacro MUI_HEADER_TEXT "Uninstalling BDMA" "Please wait while BDMA is being uninstalled..."
  ${ElseIf} $UserChoice == ${ACTION_UNINSTALL_DELETE_DATA}
    !insertmacro MUI_HEADER_TEXT "Uninstalling BDMA" "Please wait while BDMA and its data are being removed..."
  ${Else}
    !insertmacro MUI_HEADER_TEXT "Installing BDMA" "Please wait while BDMA is being installed..."
  ${EndIf}
FunctionEnd

; ── 3-option dialog ─────────────────────────────────────────────
Function ShowActionDialog
  ${If} $IsInstalled == "0"
    Abort ; Not installed -> skip this page and go straight to install
  ${EndIf}

  ${If} $AutoUpdate == "1"
    Abort ; App-triggered update starts reinstall/update without extra choice.
  ${EndIf}

  !insertmacro MUI_HEADER_TEXT "BDMA is already installed" "Please choose an action"
  GetDlgItem $0 $HWNDPARENT 1
  SendMessage $0 ${WM_SETTEXT} 0 "STR:Continue"

  nsDialogs::Create 1018
  Pop $Dialog

  ${NSD_CreateLabel} 0 0 100% 24u "BDMA is already installed on your machine. What would you like to do?"

  ${NSD_CreateRadioButton} 10u 34u 100% 14u "Reinstall / Update"
  Pop $RadioInstall
  ${NSD_SetState} $RadioInstall ${BST_CHECKED}

  ${NSD_CreateRadioButton} 10u 52u 100% 14u "Uninstall"
  Pop $RadioUninstall

  ${NSD_CreateRadioButton} 10u 70u 100% 14u "Uninstall and delete all data"
  Pop $RadioUninstallDelete

  nsDialogs::Show
FunctionEnd

Function ShowActionDialogLeave
  ${NSD_GetState} $RadioInstall $0
  GetDlgItem $1 $HWNDPARENT 1

  ${If} $0 == ${BST_CHECKED}
    StrCpy $UserChoice ${ACTION_INSTALL_UPDATE}
    SendMessage $1 ${WM_SETTEXT} 0 "STR:Install"
    Goto done
  ${EndIf}

  ${NSD_GetState} $RadioUninstall $0
  ${If} $0 == ${BST_CHECKED}
    StrCpy $UserChoice ${ACTION_UNINSTALL}
    SendMessage $1 ${WM_SETTEXT} 0 "STR:Uninstall"
    Goto done
  ${EndIf}

  StrCpy $UserChoice ${ACTION_UNINSTALL_DELETE_DATA}
  SendMessage $1 ${WM_SETTEXT} 0 "STR:Uninstall"

  done:
FunctionEnd

; ── Check whether another BDMA process is running ───────────────
; Returns the PowerShell exit code and comma-separated process IDs on the NSIS
; stack. Exit code 0 means running; any other code means not running.
Function CheckAppRunning
  ; The setup executable may also be named BDMA.exe. Exclude only this NSIS
  ; process and treat every other BDMA process as an app instance.
  System::Call 'kernel32::SetEnvironmentVariable(t, t)i("BDMA_INSTALLER_PID", "$InstallerPid").r0'
  nsExec::ExecToStack '"$PowerShellExe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "$$installerPid = [int]$$env:BDMA_INSTALLER_PID; $$running = @(Get-Process -Name BDMA -ErrorAction SilentlyContinue | Where-Object { $$_.Id -ne $$installerPid }); if ($$running.Count -eq 0) { exit 1 }; [Console]::Out.Write(($$running.Id -join $\',$\')); exit 0"'
FunctionEnd

; ── Ensure app is closed before setup changes ───────────────────
Function EnsureAppClosed
  Call CheckAppRunning
  Pop $0 ; exit code
  Pop $1 ; output

  ${If} $0 == 0
    DetailPrint "Detected running BDMA process ID(s): $1"

    ; Silent setup must remain unattended: close BDMA without prompting.
    IfSilent kill

    ; BDMA's native launcher can remain briefly after the JVM exits. Recheck
    ; after a grace period so a process already shutting down causes no prompt.
    Sleep 1000
    Call CheckAppRunning
    Pop $0 ; exit code
    Pop $1 ; output

    ${If} $0 != 0
      DetailPrint "BDMA finished closing; no forced shutdown is needed."
      Return
    ${EndIf}

    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION \
      "BDMA must be closed before setup can continue.$\r$\n$\r$\nSelect OK to close BDMA and continue, or Cancel to abort setup." \
      IDOK kill IDCANCEL cancel

    cancel:
      Abort

    kill:
      DetailPrint "Closing BDMA..."
      ; Refresh the PID list in case the app changed while the prompt was open.
      Call CheckAppRunning
      Pop $0
      Pop $1

      ${If} $0 == 0
        ; Force-kill each matched app PID and its process tree.
        nsExec::ExecToStack '"$PowerShellExe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "$$taskkill = Join-Path $$env:SystemRoot System32\taskkill.exe; @($1) | ForEach-Object { & $$taskkill /F /T /PID $$_ | Out-Null }"'
        Pop $0
        Pop $1
      ${EndIf}

      Sleep 1500
  ${EndIf}
FunctionEnd

; ── Stop BDMA-owned ADB processes left behind by forced app termination ─────
Function KillBundledAdb
  ; Kill only adb.exe processes owned by BDMA.
  ; Covers:
  ;   - $INSTDIR\...\adb.exe
  ;   - ${APP_DATA_DIR}\...\adb.exe
  ; Avoids killing Android Studio / platform-tools ADB.

  StrCpy $0 "$TEMP\bdma-kill-adb.ps1"

  FileOpen $1 $0 w

  FileWrite $1 "param([string]$$Mode)$\r$\n"
  FileWrite $1 "$$roots = @()$\r$\n"
  FileWrite $1 "$$installDir = '$INSTDIR'$\r$\n"
  FileWrite $1 "$$appDataRoot = '${APP_DATA_DIR}'$\r$\n"

  FileWrite $1 "if (Test-Path -LiteralPath $$installDir) {$\r$\n"
  FileWrite $1 "  $$roots += (Resolve-Path -LiteralPath $$installDir).Path.TrimEnd('\') + '\'$\r$\n"
  FileWrite $1 "}$\r$\n"

  FileWrite $1 "if (Test-Path -LiteralPath $$appDataRoot) {$\r$\n"
  FileWrite $1 "  $$roots += (Resolve-Path -LiteralPath $$appDataRoot).Path.TrimEnd('\') + '\'$\r$\n"
  FileWrite $1 "}$\r$\n"

  FileWrite $1 "function Get-BdmaAdbProcesses {$\r$\n"
  FileWrite $1 "  if ($$roots.Count -eq 0) { return @() }$\r$\n"
  FileWrite $1 "  @(Get-Process adb -ErrorAction SilentlyContinue | Where-Object {$\r$\n"
  FileWrite $1 "    $$processPath = $$_.Path$\r$\n"
  FileWrite $1 "    $$processPath -and ($$roots | Where-Object { $$processPath.StartsWith($$_, [System.StringComparison]::OrdinalIgnoreCase) })$\r$\n"
  FileWrite $1 "  })$\r$\n"
  FileWrite $1 "}$\r$\n"

  FileWrite $1 "$$targets = Get-BdmaAdbProcesses$\r$\n"

  FileWrite $1 "if ($$Mode -eq 'check') {$\r$\n"
  FileWrite $1 "  if ($$targets.Count -gt 0) { exit 10 }$\r$\n"
  FileWrite $1 "  exit 0$\r$\n"
  FileWrite $1 "}$\r$\n"

  FileWrite $1 "if ($$Mode -eq 'kill') {$\r$\n"
  FileWrite $1 "  for ($$attempt = 1; $$attempt -le 20; $$attempt++) {$\r$\n"
  FileWrite $1 "    $$remaining = Get-BdmaAdbProcesses$\r$\n"
  FileWrite $1 "    if ($$remaining.Count -eq 0) { break }$\r$\n"
  FileWrite $1 "    $$remaining | Stop-Process -Force -ErrorAction SilentlyContinue$\r$\n"
  FileWrite $1 "    Start-Sleep -Milliseconds 250$\r$\n"
  FileWrite $1 "  }$\r$\n"
  FileWrite $1 "  Remove-Item -LiteralPath $$PSCommandPath -Force -ErrorAction SilentlyContinue$\r$\n"
  FileWrite $1 "  exit 0$\r$\n"
  FileWrite $1 "}$\r$\n"

  FileWrite $1 "exit 0$\r$\n"

  FileClose $1

  ; First pass: check only.
  nsExec::ExecToStack '"$PowerShellExe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$0" check'
  Pop $0 ; exit code
  Pop $1 ; output

  ; No BDMA-owned adb.exe found -> print nothing.
  ${If} $0 != 10
    Delete "$TEMP\bdma-kill-adb.ps1"
    Return
  ${EndIf}

  ; Print before killing.
  DetailPrint "Stopping BDMA-owned ADB processes..."

  ; Second pass: kill.
  nsExec::ExecToStack '"$PowerShellExe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$TEMP\bdma-kill-adb.ps1" kill'
  Pop $0 ; exit code
  Pop $1 ; output

  Sleep 1500
FunctionEnd

Function PrepareForSetupChanges
  Call EnsureAppClosed
  ; ADB can outlive BDMA, so stop BDMA-owned ADB even when BDMA was already closed.
  Call KillBundledAdb
FunctionEnd

; ── Main section ────────────────────────────────────────────────
Section "Main" SecMain
  SetDetailsView show
  SetDetailsPrint both

  ; ── Uninstall only ───────────────────────────────────────────
  ${If} $UserChoice == ${ACTION_UNINSTALL}
    Call PrepareForSetupChanges
    Call DoUninstall
    Call FinishUninstall
  ${EndIf}

  ; ── Uninstall and delete app data ────────────────────────────
  ${If} $UserChoice == ${ACTION_UNINSTALL_DELETE_DATA}
    Call PrepareForSetupChanges
    Call DeleteData
    Call DoUninstall
    Call FinishUninstall
  ${EndIf}

  ; ── Install / Update ────────────────────────────────────────
  Call PrepareForSetupChanges

  ; Preserve jpackage's required layout: BDMA.exe expects its configuration and
  ; application JAR under app\, beside the runtime\ directory.
  SetOutPath "$INSTDIR"
  File "${INSTALLER_SOURCE_DIR}\BDMA.exe"

  SetOutPath "$INSTDIR\app"
  File /r "${INSTALLER_SOURCE_DIR}\app\*.*"

  SetOutPath "$INSTDIR\runtime"
  File /r "${INSTALLER_SOURCE_DIR}\runtime\*.*"

  ${If} $EXEPATH != "$INSTDIR\BDMA-Setup.exe"
    CopyFiles "$EXEPATH" "$INSTDIR\BDMA-Setup.exe"
  ${EndIf}

  WriteRegStr HKLM "Software\BDMA" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "DisplayName" "BDMA"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "UninstallString" '"$INSTDIR\BDMA-Setup.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "DisplayIcon" "$INSTDIR\BDMA.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "Publisher" "DVID"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "DisplayVersion" "${APP_VERSION}"
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "NoRepair" 1

  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA" \
    "EstimatedSize" $0

  ; Create shortcuts with the install root as their working directory. $OUTDIR
  ; still points to runtime after copying the jpackage image.
  SetOutPath "$INSTDIR"
  CreateShortcut "$DESKTOP\BDMA.lnk" "$INSTDIR\BDMA.exe"
  CreateDirectory "$SMPROGRAMS\BDMA"
  CreateShortcut "$SMPROGRAMS\BDMA\BDMA.lnk" "$INSTDIR\BDMA.exe"
  CreateShortcut "$SMPROGRAMS\BDMA\Gỡ cài đặt.lnk" "$INSTDIR\BDMA-Setup.exe"

  ; Disable AutoPlay to prevent Windows popup when body camera connected
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Policies\Explorer" "NoDriveTypeAutoRun" 0xFF

  ${If} $AutoOpen == "1"
    DetailPrint "Opening BDMA..."
    Exec '"$INSTDIR\BDMA.exe"'
  ${EndIf}
SectionEnd

; ── Finish uninstall after normal cleanup has completed ─────────
Function FinishUninstall
  ${If} $UserChoice == ${ACTION_UNINSTALL_DELETE_DATA}
    MessageBox MB_OK "BDMA and its data have been uninstalled successfully!"
  ${Else}
    MessageBox MB_OK "BDMA has been uninstalled successfully!"
  ${EndIf}

  ; Launch delayed cleanup only after user closes the success dialog.
  ; This is important when the uninstaller is running from $INSTDIR\BDMA-Setup.exe.
  ${If} $DelayedCleanupNeeded == "1"
    SetOutPath "$TEMP"
    ExecShell "open" "$SYSDIR\wscript.exe" '"$CleanupLauncherPath"' SW_HIDE
  ${EndIf}

  Quit
FunctionEnd

; ── Delete app data using native NSIS commands ──────────────────
Function DeleteData
  ; Keep existing safety check.
  StrCpy $0 "${APP_DATA_DIR}" 4 -4
  ${If} $0 != "bdma"
    MessageBox MB_OK|MB_ICONSTOP "Refusing to delete unsafe app data path: ${APP_DATA_DIR}"
    Abort
  ${EndIf}

  SetDetailsView show
  SetDetailsPrint both

  DetailPrint "Deleting BDMA app data: ${APP_DATA_DIR}"

  IfFileExists "${APP_DATA_DIR}" 0 dataDeleted

  StrCpy $0 "0"

  deleteRetry:
    IntOp $0 $0 + 1
    DetailPrint "Deleting app data attempt $0: ${APP_DATA_DIR}"
    ClearErrors
    RMDir /r "${APP_DATA_DIR}"

    IfFileExists "${APP_DATA_DIR}" dataStillExists dataDeleted

  dataStillExists:
    ${If} $0 < 20
      Sleep 250
      Goto deleteRetry
    ${EndIf}

    MessageBox MB_OK|MB_ICONEXCLAMATION \
      "BDMA could not delete some app data.$\r$\n$\r$\nPath:$\r$\n${APP_DATA_DIR}$\r$\n$\r$\nPlease close any program using this folder and delete it manually if needed."
    Return

  dataDeleted:
    DetailPrint "BDMA app data deleted or not present."
FunctionEnd

; ── Uninstall using native NSIS commands ────────────────────────
Function DoUninstall
  SetDetailsView show
  SetDetailsPrint both

  ; Do not keep the current working directory inside $INSTDIR.
  ; This matters when uninstall is launched from $INSTDIR\BDMA-Setup.exe.
  SetOutPath "$TEMP"

  DetailPrint "Removing BDMA installation files: $INSTDIR"

  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\BDMA.exe"

  Delete "$DESKTOP\BDMA.lnk"
  Delete "$SMPROGRAMS\BDMA\BDMA.lnk"
  Delete "$SMPROGRAMS\BDMA\Uninstall.lnk"
  RMDir "$SMPROGRAMS\BDMA"

  DeleteRegKey HKLM "Software\BDMA"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA"
  DeleteRegValue HKLM "Software\Microsoft\Windows\CurrentVersion\Policies\Explorer" "NoDriveTypeAutoRun"

  DetailPrint "Removing installer copy and install directory..."

  Delete "$INSTDIR\BDMA-Setup.exe"
  RMDir /r "$INSTDIR"

  IfFileExists "$INSTDIR" installDirStillExists installDirDeleted

  installDirStillExists:
    DetailPrint "Install directory still exists, scheduling delayed cleanup: $INSTDIR"

    ; Keep the delayed cleanup process outside the directory it needs to delete.
    SetOutPath "$TEMP"

    ; Capture current installer PID so the external cleanup script can wait until
    ; this NSIS process exits. This is the key fix for uninstall-from-$INSTDIR.
    System::Call 'kernel32::GetCurrentProcessId() i .r3'

    StrCpy $0 "$TEMP\bdma-cleanup.ps1"
    StrCpy $2 "$TEMP\bdma-cleanup.vbs"

    FileOpen $1 $0 w
    FileWrite $1 "param([int]$$ParentPid)$\r$\n"
    FileWrite $1 "$$ErrorActionPreference = 'Continue'$\r$\n"
    FileWrite $1 "$$installDir = '$INSTDIR'$\r$\n"
    FileWrite $1 "$$launcherPath = '$2'$\r$\n"
    FileWrite $1 "$$logPath = Join-Path $$env:TEMP 'bdma-cleanup.log'$\r$\n"
    FileWrite $1 "function Log([string]$$message) {$\r$\n"
    FileWrite $1 "  Add-Content -LiteralPath $$logPath -Value ($$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff') + ' ' + $$message) -ErrorAction SilentlyContinue$\r$\n"
    FileWrite $1 "}$\r$\n"
    FileWrite $1 "try { Set-Location -LiteralPath $$env:TEMP } catch {}$\r$\n"
    FileWrite $1 "Log ('Cleanup started. ParentPid=' + $$ParentPid + ' InstallDir=' + $$installDir)$\r$\n"

    ; Wait until the installer process exits. Until then, $INSTDIR\BDMA-Setup.exe
    ; can still be locked because it is the currently running executable.
    FileWrite $1 "if ($$ParentPid -gt 0) {$\r$\n"
    FileWrite $1 "  for ($$i = 1; $$i -le 120; $$i++) {$\r$\n"
    FileWrite $1 "    $$parent = Get-Process -Id $$ParentPid -ErrorAction SilentlyContinue$\r$\n"
    FileWrite $1 "    if (-not $$parent) { break }$\r$\n"
    FileWrite $1 "    Start-Sleep -Milliseconds 500$\r$\n"
    FileWrite $1 "  }$\r$\n"
    FileWrite $1 "}$\r$\n"

    ; Give Windows a little extra time to release the EXE handle.
    FileWrite $1 "Start-Sleep -Milliseconds 800$\r$\n"

    ; Safety guard: only delete the expected BDMA install directory.
    FileWrite $1 "if ([string]::IsNullOrWhiteSpace($$installDir)) { Log 'Refusing cleanup: empty installDir'; exit 2 }$\r$\n"
    FileWrite $1 "if ((Split-Path -Leaf $$installDir) -ne 'BDMA') { Log ('Refusing cleanup: unexpected installDir=' + $$installDir); exit 3 }$\r$\n"

    ; Retry deletion after the installer has exited.
    FileWrite $1 "for ($$attempt = 1; $$attempt -le 60; $$attempt++) {$\r$\n"
    FileWrite $1 "  try {$\r$\n"
    FileWrite $1 "    if (Test-Path -LiteralPath $$installDir) {$\r$\n"
    FileWrite $1 "      Log ('Delete attempt ' + $$attempt)$\r$\n"
    FileWrite $1 "      Remove-Item -LiteralPath $$installDir -Recurse -Force -ErrorAction Stop$\r$\n"
    FileWrite $1 "    }$\r$\n"
    FileWrite $1 "    if (-not (Test-Path -LiteralPath $$installDir)) {$\r$\n"
    FileWrite $1 "      Log 'Install directory removed.'$\r$\n"
    FileWrite $1 "      break$\r$\n"
    FileWrite $1 "    }$\r$\n"
    FileWrite $1 "  } catch {$\r$\n"
    FileWrite $1 "    Log ('Delete attempt ' + $$attempt + ' failed: ' + $$_.Exception.Message)$\r$\n"
    FileWrite $1 "  }$\r$\n"
    FileWrite $1 "  Start-Sleep -Milliseconds 500$\r$\n"
    FileWrite $1 "}$\r$\n"

    ; Cleanup temp launcher/script.
    FileWrite $1 "Remove-Item -LiteralPath $$launcherPath -Force -ErrorAction SilentlyContinue$\r$\n"
    FileWrite $1 "Remove-Item -LiteralPath $$PSCommandPath -Force -ErrorAction SilentlyContinue$\r$\n"
    FileClose $1

    FileOpen $1 $2 w
    FileWrite $1 "q = Chr(34)$\r$\n"
    FileWrite $1 "Set sh = CreateObject($\"WScript.Shell$\")$\r$\n"
    FileWrite $1 "sh.CurrentDirectory = $\"$TEMP$\"$\r$\n"
    FileWrite $1 "ps = $\"$PowerShellExe$\"$\r$\n"
    FileWrite $1 "script = $\"$0$\"$\r$\n"
    FileWrite $1 "parentPid = $\"$3$\"$\r$\n"
    FileWrite $1 "cmd = q & ps & q & $\" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File $\" & q & script & q & $\" -ParentPid $\" & parentPid$\r$\n"
    FileWrite $1 "sh.Run cmd, 0, False$\r$\n"
    FileClose $1

    StrCpy $DelayedCleanupNeeded "1"
    StrCpy $CleanupLauncherPath "$2"
    Goto done

  installDirDeleted:
    DetailPrint "BDMA install directory removed."

  done:
    DetailPrint "BDMA uninstall file cleanup completed."
FunctionEnd
