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

; ── Trang chọn action (chỉ hiện khi đã cài) ─────────────────────
Page custom ShowActionDialog ShowActionDialogLeave
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

; ── Detect đã cài chưa ──────────────────────────────────────────
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

; ── Dialog 3 lựa chọn ───────────────────────────────────────────
Function ShowActionDialog
  ${If} $IsInstalled == "0"
    Abort ; Chưa cài → bỏ qua trang này, install thẳng
  ${EndIf}

  !insertmacro MUI_HEADER_TEXT "BDMA đã được cài đặt" "Vui lòng chọn hành động"
  GetDlgItem $0 $HWNDPARENT 1
  SendMessage $0 ${WM_SETTEXT} 0 "STR:Tiếp tục"

  nsDialogs::Create 1018
  Pop $Dialog

  ${NSD_CreateLabel} 0 0 100% 24u "BDMA đã được cài đặt trên máy của bạn. Bạn muốn làm gì?"

  ${NSD_CreateRadioButton} 10u 34u 100% 14u "Cài đặt lại / Cập nhật"
  Pop $RadioInstall
  ${NSD_SetState} $RadioInstall ${BST_CHECKED}

  ${NSD_CreateRadioButton} 10u 52u 100% 14u "Gỡ cài đặt"
  Pop $RadioUninstall

  ${NSD_CreateRadioButton} 10u 70u 100% 14u "Gỡ cài đặt và xóa toàn bộ dữ liệu"
  Pop $RadioUninstallDelete

  nsDialogs::Show
FunctionEnd

Function ShowActionDialogLeave
  ${NSD_GetState} $RadioInstall $0
  GetDlgItem $1 $HWNDPARENT 1

  ${If} $0 == ${BST_CHECKED}
    StrCpy $UserChoice "1"
    SendMessage $1 ${WM_SETTEXT} 0 "STR:Cài đặt"
    Goto done
  ${EndIf}

  ${NSD_GetState} $RadioUninstall $0
  ${If} $0 == ${BST_CHECKED}
    StrCpy $UserChoice "2"
    SendMessage $1 ${WM_SETTEXT} 0 "STR:Gỡ cài đặt"
    Goto done
  ${EndIf}

  StrCpy $UserChoice "3"
  SendMessage $1 ${WM_SETTEXT} 0 "STR:Gỡ cài đặt"
  done:
FunctionEnd

; ── Đảm bảo app đã đóng trước khi gỡ ───────────────────────────
Function EnsureAppClosed
  nsExec::ExecToStack '$SYSDIR\cmd.exe /C tasklist /FI "IMAGENAME eq BDMA.exe" /NH | find /I "BDMA.exe"'
  Pop $0 ; exit code
  Pop $1 ; output

  ${If} $0 == 0
    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION \
      "BDMA đang chạy. Nhấn OK để đóng ứng dụng và tiếp tục gỡ cài đặt." \
      IDOK kill IDCANCEL cancel
    cancel:
      Abort
    kill:
      ExecWait '$SYSDIR\taskkill.exe /F /IM BDMA.exe'
      Sleep 1500
  ${EndIf}
FunctionEnd

; ── Section chính ───────────────────────────────────────────────
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
  CreateShortcut "$SMPROGRAMS\BDMA\Gỡ cài đặt.lnk" "$INSTDIR\BDMA-Setup.exe"

  MessageBox MB_OK "Cài đặt BDMA thành công!"
SectionEnd

; ── Uninstall ───────────────────────────────────────────────────
Function DoUninstall
  Call EnsureAppClosed

  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\BDMA.exe"

  Delete "$DESKTOP\BDMA.lnk"
  Delete "$SMPROGRAMS\BDMA\BDMA.lnk"
  Delete "$SMPROGRAMS\BDMA\Gỡ cài đặt.lnk"
  RMDir "$SMPROGRAMS\BDMA"

  DeleteRegKey HKLM "Software\BDMA"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\BDMA"

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

  MessageBox MB_OK "Gỡ cài đặt BDMA thành công!"
FunctionEnd

; ── Xóa data ────────────────────────────────────────────────────
Function DeleteData
  RMDir /r "${APP_DATA_DIR}"
  ExecWait '$SYSDIR\cmd.exe /C attrib -R -H -S "$\"${APP_DATA_DIR}$\"" /S /D'
  ExecWait '$SYSDIR\cmd.exe /C rmdir /S /Q "$\"${APP_DATA_DIR}$\""'
FunctionEnd