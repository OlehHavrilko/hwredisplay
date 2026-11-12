# Рабочая оболочка для сборки HardwareDisplay на этой машине.
# Если переменные ProgramFiles пустые, NuGet при загрузке machine-wide config
# падает с "Value cannot be null. (Parameter 'path1')" — заполняем их перед dotnet.
if (-not $env:ProgramFiles) { $env:ProgramFiles = 'C:\Program Files' }
if (-not ${env:ProgramFiles(x86)}) { ${env:ProgramFiles(x86)} = 'C:\Program Files (x86)' }
if (-not $env:ProgramW6432) { $env:ProgramW6432 = $env:ProgramFiles }
if (-not $env:CommonProgramFiles) { $env:CommonProgramFiles = 'C:\Program Files\Common Files' }

# Узлы MSBuild переиспользуются со старым окружением, поэтому отключаем их повторное использование.
$env:MSBUILDDISABLENODEREUSE = '1'
