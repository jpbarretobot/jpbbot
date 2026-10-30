@echo off
REM Cree dist\EasyMod.exe (un seul fichier, a partager sans installer Python)
cd /d "%~dp0"
python -m pip install -r requirements-dev.txt || goto :error
python -m PyInstaller --noconfirm --onefile --windowed --name EasyMod --collect-all tkinterdnd2 easymod_launcher.py || goto :error
echo.
echo Termine : le programme se trouve dans le dossier dist\EasyMod.exe
pause
exit /b 0
:error
echo La creation a echoue.
pause
exit /b 1
