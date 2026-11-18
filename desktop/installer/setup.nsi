; MRM Zone Internet Download Manager — Windows installer (per-user, no admin needed)
Unicode true
SetCompressor /SOLID lzma
!include "MUI2.nsh"
!include "FileFunc.nsh"

!define APPNAME "MRM Zone IDM"
!define FULLNAME "MRM Zone Internet Download Manager"
!define EXE "MRMZoneIDM.exe"
!ifndef VERSION
  !define VERSION "1.0.0"
!endif
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\MRMZoneIDM"

Name "${FULLNAME}"
OutFile "..\..\release\MRM-Zone-IDM-Setup-${VERSION}.exe"
InstallDir "$LOCALAPPDATA\Programs\MRM Zone IDM"
InstallDirRegKey HKCU "Software\MRM Zone IDM" "InstallDir"
RequestExecutionLevel user
BrandingText "MRM Zone"
VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "${FULLNAME}"
VIAddVersionKey "CompanyName" "MRM Zone"
VIAddVersionKey "FileDescription" "${FULLNAME} Setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "MRM Zone"

!define MUI_ICON "..\MRMZoneIDM\Assets\app.ico"
!define MUI_UNICON "..\MRMZoneIDM\Assets\app.ico"
!define MUI_WELCOMEPAGE_TITLE "Welcome to ${FULLNAME}"
!define MUI_WELCOMEPAGE_TEXT "This will install ${FULLNAME} ${VERSION} on your computer.$\r$\n$\r$\n• Fast multi-connection downloads with pause & resume$\r$\n• YouTube and 1000+ video sites in every quality$\r$\n• Chrome / Edge extension: 'Download this video'$\r$\n• Queue, scheduler, speed limiter, site grabber$\r$\n$\r$\nClick Next to continue."
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Launch MRM Zone IDM (and set up the browser extension)"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Install"
  ; close a running copy so files can be replaced
  nsExec::Exec 'taskkill /F /IM ${EXE}'
  Sleep 500
  SetOutPath "$INSTDIR"
  File "..\publish\${EXE}"
  File "..\MRMZoneIDM\Assets\app.ico"
  SetOutPath "$INSTDIR\extension"
  File /r "..\extension\*.*"
  SetOutPath "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\MRM Zone IDM" "InstallDir" "$INSTDIR"

  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\app.ico"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Browser extension folder.lnk" "$INSTDIR\extension"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Uninstall ${APPNAME}.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\app.ico"

  ; Add/Remove Programs entry
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayName" "${FULLNAME}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTKEY}" "Publisher" "MRM Zone"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\app.ico"
  WriteRegStr HKCU "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKCU "${UNINSTKEY}" "EstimatedSize" "$0"
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /F /IM ${EXE}'
  Sleep 500
  Delete "$INSTDIR\${EXE}"
  Delete "$INSTDIR\app.ico"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir /r "$INSTDIR\extension"
  RMDir "$INSTDIR"
  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$SMPROGRAMS\${APPNAME}"
  DeleteRegKey HKCU "${UNINSTKEY}"
  DeleteRegKey HKCU "Software\MRM Zone IDM"
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "MRMZoneIDM"
  ; downloads, settings and the video engine in %LOCALAPPDATA% are kept on purpose
SectionEnd
