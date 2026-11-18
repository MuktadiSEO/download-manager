#!/bin/bash
# Builds MRMZoneIDM.exe (self-contained, win-x64) and the NSIS installer. Run on Linux or Windows (Git Bash).
set -e
cd "$(dirname "$0")"
VERSION=${VERSION:-1.0.0}
DOTNET=${DOTNET:-dotnet}
"$DOTNET" publish MRMZoneIDM/MRMZoneIDM.csproj -c Release -o publish -p:Version=$VERSION
makensis -DVERSION=$VERSION installer/setup.nsi
