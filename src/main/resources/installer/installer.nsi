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
!define APP_DATA_DIR "$PROFILE\\.helloworld-app"
!endif

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

; ── Action selection page (only shown when already installed) ────
Page custom ShowActionDialog ShowActionDialogLeave
!define MUI_PAGE_CUSTOMFUNCTION_SHOW InstFilesPageShow
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

; ── Check if already installed ──────────────────────────────────
Function .onInit
  ReadRegStr $IsInstalled HKLM "Software\BDMA" "InstallDir"
  ${If} $IsInstalled == ""
    Goto notInstalled
  ${EndIf}

  IfFileExists "$IsInstalled\BDMA.exe" installed notInstalled

  installed:
    StrCpy $IsInstalled "1"
    Return

  notInstalled:
    DeleteRegKey HKLM "Software\BDMA"
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA"
    StrCpy $IsInstalled "0"
    StrCpy $UserChoice "1"
FunctionEnd

; ── Override instfiles page header based on action ──────────────
Function InstFilesPageShow
  ${If} $UserChoice == "2"
    !insertmacro MUI_HEADER_TEXT "Uninstall BDMA" "Please wait while BDMA is being uninstalled..."
  ${ElseIf} $UserChoice == "3"
    !insertmacro MUI_HEADER_TEXT "Uninstall BDMA" "Please wait while BDMA and its data are being removed..."
  ${Else}
    !insertmacro MUI_HEADER_TEXT "Install BDMA" "Please wait while BDMA is being installed..."
  ${EndIf}
FunctionEnd

; ── 3-option dialog ─────────────────────────────────────────────
Function ShowActionDialog
  ${If} $IsInstalled == "0"
    Abort ; Not installed → skip this page and go straight to install
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
    StrCpy $UserChoice "1"
    SendMessage $1 ${WM_SETTEXT} 0 "STR:Install"
    Goto done
  ${EndIf}

  ${NSD_GetState} $RadioUninstall $0
  ${If} $0 == ${BST_CHECKED}
    StrCpy $UserChoice "2"
    SendMessage $1 ${WM_SETTEXT} 0 "STR:Uninstall"
    Goto done
  ${EndIf}

  StrCpy $UserChoice "3"
  SendMessage $1 ${WM_SETTEXT} 0 "STR:Uninstall"
  done:
FunctionEnd

; ── Ensure app is closed before uninstalling ────────────────────
Function EnsureAppClosed
  nsExec::ExecToStack '$SYSDIR\cmd.exe /C tasklist /FI "IMAGENAME eq BDMA.exe" /NH | find /I "BDMA.exe"'
  Pop $0 ; exit code
  Pop $1 ; output

  ${If} $0 == 0
    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION \
      "BDMA is currently running. Click OK to close the application and continue uninstalling." \
      IDOK kill IDCANCEL cancel
    cancel:
      Abort
    kill:
      ExecWait '$SYSDIR\taskkill.exe /F /IM BDMA.exe'
      Sleep 1500
  ${EndIf}
FunctionEnd

; ── Main section ────────────────────────────────────────────────
Section "Main" SecMain
  ${If} $UserChoice == "2"
    Call DoUninstall
    Quit
  ${EndIf}

  ${If} $UserChoice == "3"
    Call DeleteData
    Call DoUninstall
    Quit
  ${EndIf}

  ; ── Install / Update ────────────────────────────────────────
  SetOutPath "$INSTDIR"
  File /r "${INSTALLER_SOURCE_DIR}\*.*"

  CopyFiles "$EXEPATH" "$INSTDIR\BDMA-Setup.exe"

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

  CreateShortcut "$DESKTOP\BDMA.lnk" "$INSTDIR\BDMA.exe"
  CreateDirectory "$SMPROGRAMS\BDMA"
  CreateShortcut "$SMPROGRAMS\BDMA\BDMA.lnk" "$INSTDIR\BDMA.exe"
  CreateShortcut "$SMPROGRAMS\BDMA\Uninstall.lnk" "$INSTDIR\BDMA-Setup.exe"

  ; Disable AutoPlay to prevent Windows popup when body camera connected
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Policies\Explorer" "NoDriveTypeAutoRun" 0xFF

  MessageBox MB_OK "BDMA has been installed successfully!"
SectionEnd

; ── Uninstall ───────────────────────────────────────────────────
Function DoUninstall
  Call EnsureAppClosed

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

  StrCpy $0 "$TEMP\bdma-cleanup.cmd"
  FileOpen $1 $0 w
  FileWrite $1 "@echo off$\r$\n"
  FileWrite $1 ":retry$\r$\n"
  FileWrite $1 "ping 127.0.0.1 -n 3 > nul$\r$\n"
  FileWrite $1 "del /F /Q $\"$EXEPATH$\"$\r$\n"
  FileWrite $1 "if exist $\"$EXEPATH$\" goto retry$\r$\n"
  FileWrite $1 "rmdir /S /Q $\"$INSTDIR$\"$\r$\n"
  FileWrite $1 "del /F /Q %~f0$\r$\n"
  FileClose $1
  Exec '"$SYSDIR\cmd.exe" /C start "" /min "$0"'

  MessageBox MB_OK "BDMA has been uninstalled successfully!"
FunctionEnd

; ── Delete app data ─────────────────────────────────────────────
Function DeleteData
  StrCpy $0 "${APP_DATA_DIR}" 4 -4
  ${If} $0 != "bdma"
    MessageBox MB_OK|MB_ICONSTOP "Refusing to delete unsafe app data path: ${APP_DATA_DIR}"
    Abort
  ${EndIf}

  ExecWait '$SYSDIR\cmd.exe /C attrib -R -H -S "$\"${APP_DATA_DIR}$\"" /S /D'
  RMDir /r "${APP_DATA_DIR}"
  ExecWait '$SYSDIR\cmd.exe /C rmdir /S /Q "$\"${APP_DATA_DIR}$\""'
FunctionEnd