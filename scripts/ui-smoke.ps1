param(
    [string]$Exe = 'windows\HardwareDisplay.Host\bin\Debug\net10.0-windows\HardwareDisplay.Host.exe',
    [switch]$KeepOpen
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$automation = [System.Windows.Automation.AutomationElement]
$treeScope = [System.Windows.Automation.TreeScope]

$process = Start-Process -FilePath $Exe -PassThru
Start-Sleep -Seconds 5

function Get-Window {
    $condition = New-Object System.Windows.Automation.PropertyCondition($automation::NameProperty, 'HardwareDisplay')
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $window = $automation::RootElement.FindFirst($treeScope::Children, $condition)
        if ($window) { return $window }
        Start-Sleep -Milliseconds 300
    }
    throw 'Окно HardwareDisplay не найдено'
}

function Get-Texts($window) {
    $condition = New-Object System.Windows.Automation.PropertyCondition($automation::ControlTypeProperty, [System.Windows.Automation.ControlType]::Text)
    $window.FindAll($treeScope::Descendants, $condition) |
        ForEach-Object { $_.Current.Name } |
        Where-Object { $_ }
}

function Invoke-Button($window, $name) {
    $nameCondition = New-Object System.Windows.Automation.PropertyCondition($automation::NameProperty, $name)
    $typeCondition = New-Object System.Windows.Automation.PropertyCondition($automation::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
    $condition = New-Object System.Windows.Automation.AndCondition($nameCondition, $typeCondition)
    $button = $window.FindFirst($treeScope::Descendants, $condition)
    if (-not $button) { throw "Кнопка '$name' не найдена" }

    $button.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
}

$window = Get-Window
$initial = Get-Texts $window

Invoke-Button $window 'Остановить сервер'
Start-Sleep -Seconds 2
$stopped = Get-Texts $window

Invoke-Button $window 'Запустить сервер'
Start-Sleep -Seconds 3
$restarted = Get-Texts $window

if (-not $KeepOpen) {
    $process | Stop-Process -Force
}

'--- старт ---'
$initial | Select-Object -First 12
'--- после остановки ---'
$stopped | Select-Object -First 6
'--- после повторного запуска ---'
$restarted | Select-Object -First 12

$checks = [ordered]@{
    'сервер работал'        = $initial -contains 'Сервер работает'
    'есть адрес'            = [bool]($initial | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+:\d+$' })
    'есть код приглашения'  = [bool]($initial | Where-Object { $_ -match '^[0-9a-f]{16,}$' })
    'есть показание CPU'    = [bool]($initial | Where-Object { $_ -match '^\d+([.,]\d+)? %$' })
    'остановка сработала'   = $stopped -contains 'Сервер остановлен'
    'повторный запуск'      = $restarted -contains 'Сервер работает'
}

$failed = $checks.GetEnumerator() | Where-Object { -not $_.Value }
if ($failed) {
    'ПРОВАЛ: ' + (($failed | ForEach-Object { $_.Key }) -join ', ')
    exit 1
}

'Все проверки интерфейса пройдены'
