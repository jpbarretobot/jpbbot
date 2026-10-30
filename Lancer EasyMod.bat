@echo off
REM Lance EasyMod sans fenetre de console
cd /d "%~dp0"
start "" pythonw -m easymod %*
